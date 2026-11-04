package com.stepcounter.app;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Проверка логики обязательства без телефона.
 *
 *   javac -encoding UTF-8 -d out app/src/main/java/com/stepcounter/app/{StepSource,Commitment,Ledger,PaymentProvider,VirtualVault,Settler}.java tools/com/stepcounter/app/CoreTest.java
 *   java -cp out com.stepcounter.app.CoreTest
 */
public class CoreTest {
    static int failed = 0;
    static int passed = 0;

    static void check(String name, boolean ok) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
    }

    static final LocalDate D1 = LocalDate.of(2026, 11, 1);
    static final LocalDate D4 = LocalDate.of(2026, 11, 4);

    /** Шаги по дням из карты; отсутствующий день = 0 шагов. */
    static Settler.StepsLookup steps(final Map<LocalDate, Integer> own, final Map<LocalDate, Integer> hw) {
        return new Settler.StepsLookup() {
            public int own(LocalDate d) { return own.getOrDefault(d, 0); }
            public Integer hw(LocalDate d) { return hw == null ? null : hw.getOrDefault(d, 0); }
        };
    }

    /** Провайдер, который можно "сломать", и который считает вызовы и реально списанное по ключам. */
    static class FakeBank implements PaymentProvider {
        boolean down;
        int calls;
        final Map<String, Long> charged = new HashMap<>();
        public Result transfer(String key, long amount, String cur) {
            calls++;
            if (down) return Result.fail("банк недоступен");
            charged.putIfAbsent(key, amount); // идемпотентность: повтор ключа ничего не меняет
            return Result.ok("ref-" + key);
        }
        long total() { long t = 0; for (long v : charged.values()) t += v; return t; }
    }

    static Ledger newLedger() throws Exception {
        File f = File.createTempFile("ledger", ".log");
        f.delete();
        f.deleteOnExit();
        return new Ledger(f);
    }

    static Map<LocalDate, Integer> days(int... v) {
        Map<LocalDate, Integer> m = new HashMap<>();
        for (int i = 0; i < v.length; i++) m.put(D1.plusDays(i), v[i]);
        return m;
    }

    static Commitment base() {
        return Commitment.create(D1, 8000, 50000, "RUB", StepSource.HW);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Штрафы за невыполненные дни");
        {
            Ledger l = newLedger();
            FakeBank bank = new FakeBank();
            List<Settler.Result> r = Settler.settle(D4, base(), steps(days(9000, 3000, 8000), days(9000, 3000, 8000)), l, bank);
            check("оценено 3 дня (1,2,3 ноября), сегодняшний не тронут", r.size() == 3);
            check("день 1 (9000) выполнен", r.get(0).met && r.get(0).penaltyMinor == 0);
            check("день 2 (3000) не выполнен, штраф 500.00", !r.get(1).met && r.get(1).penaltyMinor == 50000);
            check("день 3 (ровно 8000) выполнен: граница включительно", r.get(2).met);
            Settler.Summary s = Settler.summarize(l);
            check("в копилке 500.00, ожидающих нет", s.transferredMinor == 50000 && s.pendingMinor == 0);
            check("статистика 2 выполнено / 1 нет", s.metDays == 2 && s.missedDays == 1);
        }

        System.out.println("Повторный запуск не дублирует");
        {
            Ledger l = newLedger();
            FakeBank bank = new FakeBank();
            Map<LocalDate, Integer> m = days(9000, 3000, 8000);
            Settler.settle(D4, base(), steps(m, m), l, bank);
            List<Settler.Result> again = Settler.settle(D4, base(), steps(m, m), l, bank);
            Settler.Summary s = Settler.summarize(l);
            check("второй вызов: новых дней нет", again.isEmpty());
            check("штраф не удвоился", s.transferredMinor == 50000 && bank.total() == 50000);
            check("в журнале по дню записано один раз", l.records().size() == 4); // 3 DAY + 1 TRANSFER
        }

        System.out.println("Сбой банка: штраф ждёт и уходит позже");
        {
            Ledger l = newLedger();
            FakeBank bank = new FakeBank();
            bank.down = true;
            Map<LocalDate, Integer> m = days(100, 100, 100);
            Settler.settle(D4, base(), steps(m, m), l, bank);
            Settler.Summary s1 = Settler.summarize(l);
            check("пока банк лежит: к переводу 1500.00, в копилке 0", s1.pendingMinor == 150000 && s1.transferredMinor == 0);
            bank.down = false;
            Settler.settle(D4, base(), steps(m, m), l, bank);
            Settler.Summary s2 = Settler.summarize(l);
            check("после восстановления всё ушло", s2.pendingMinor == 0 && s2.transferredMinor == 150000);
            check("банк списал ровно 1500.00, без дублей", bank.total() == 150000);
        }

        System.out.println("Телефон был выключен несколько дней");
        {
            Ledger l = newLedger();
            FakeBank bank = new FakeBank();
            Map<LocalDate, Integer> none = days();
            List<Settler.Result> r = Settler.settle(D1.plusDays(5), base(), steps(none, none), l, bank);
            check("5 пропущенных дней оценены разом, все невыполненные", r.size() == 5 && !r.get(0).met);
            check("штраф за каждый день", Settler.summarize(l).transferredMinor == 5 * 50000);
        }

        System.out.println("Обязательство ещё не началось");
        {
            Ledger l = newLedger();
            Commitment later = Commitment.create(D4, 8000, 50000, "RUB", StepSource.HW);
            Map<LocalDate, Integer> none = days();
            check("до даты старта дни не оцениваются", Settler.settle(D4, later, steps(none, none), l, new FakeBank()).isEmpty());
        }

        System.out.println("Ослабить цель нельзя сразу");
        {
            Commitment c = base();
            Commitment tighter = c.withChange(10000, 50000, D4);
            check("повышение цели действует сегодня", tighter.goalFor(D4) == 10000);
            Commitment looser = c.withChange(4000, 50000, D4);
            check("понижение цели сегодня не действует", looser.goalFor(D4) == 8000);
            check("и через 6 дней тоже", looser.goalFor(D4.plusDays(6)) == 8000);
            check("начинает действовать через 7 дней", looser.goalFor(D4.plusDays(7)) == 4000);
            Commitment lowPen = c.withChange(8000, 10000, D4);
            check("снижение штрафа тоже откладывается", lowPen.penaltyFor(D4) == 50000 && lowPen.penaltyFor(D4.plusDays(7)) == 10000);
            Commitment mixed = c.withChange(12000, 10000, D4);
            check("цель вверх, штраф вниз: цель сразу, штраф через неделю",
                    mixed.goalFor(D4) == 12000 && mixed.penaltyFor(D4) == 50000 && mixed.penaltyFor(D4.plusDays(7)) == 10000);
            check("новая просьба заменяет отложенную и не обнуляет защиту",
                    looser.withChange(8000, 50000, D4.plusDays(2)).goalFor(D4.plusDays(2)) == 8000);
            check("ослабление уже вступило в силу и стало основным",
                    looser.withChange(4000, 50000, D4.plusDays(8)).goalFor(D4.plusDays(8)) == 4000);
        }

        System.out.println("Правила действуют на день, а не на момент расчёта");
        {
            Ledger l = newLedger();
            Commitment c = base().withChange(4000, 50000, D1); // ослабление с 8 ноября
            Map<LocalDate, Integer> m = days(5000);
            Settler.settle(D1.plusDays(1), c, steps(m, m), l, new FakeBank());
            check("5000 шагов 1 ноября при цели 8000 = штраф, хотя новая цель была бы 4000",
                    Settler.summarize(l).missedDays == 1);
        }

        System.out.println("Источник шагов");
        {
            Map<LocalDate, Integer> own = days(9000);
            Map<LocalDate, Integer> hw = days(3000);
            Ledger l1 = newLedger();
            Settler.settle(D1.plusDays(1), Commitment.create(D1, 8000, 50000, "RUB", StepSource.HW), steps(own, hw), l1, new FakeBank());
            check("HW: свой алгоритм насчитал много, но шагомер мало: штраф", Settler.summarize(l1).missedDays == 1);
            Ledger l2 = newLedger();
            Settler.settle(D1.plusDays(1), Commitment.create(D1, 8000, 50000, "RUB", StepSource.MAX), steps(own, hw), l2, new FakeBank());
            check("MAX: берётся большее, штрафа нет", Settler.summarize(l2).missedDays == 0);
            Ledger l3 = newLedger();
            Settler.settle(D1.plusDays(1), Commitment.create(D1, 8000, 50000, "RUB", StepSource.HW), steps(own, null), l3, new FakeBank());
            check("нет аппаратного шагомера: используется свой счёт", Settler.summarize(l3).missedDays == 0);
            check("расхождение датчиков помечается", Settler.diverges(9000, 3000) && !Settler.diverges(9000, 8500));
        }

        System.out.println("Подделка журнала");
        {
            File f = File.createTempFile("ledger", ".log");
            f.delete();
            f.deleteOnExit();
            Ledger l = new Ledger(f);
            Map<LocalDate, Integer> m = days(100, 100);
            Settler.settle(D1.plusDays(2), base(), steps(m, m), l, new FakeBank());
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Files.write(f.toPath(), text.replace(";MISSED;50000;", ";MET;0;").getBytes(StandardCharsets.UTF_8));
            boolean caught = false;
            try { l.records(); } catch (Ledger.TamperedException e) { caught = true; }
            check("изменение записи (штраф -> выполнено) обнаружено", caught);
            boolean refused = false;
            try { l.append("DAY", "2026-12-01", "1", "1", "MET", "0", "RUB", "-"); } catch (Ledger.TamperedException e) { refused = true; }
            check("в испорченный журнал новые записи не добавляются", refused);

            // удаление строки из середины
            File f2 = File.createTempFile("ledger", ".log");
            f2.delete();
            f2.deleteOnExit();
            Ledger l2 = new Ledger(f2);
            Settler.settle(D1.plusDays(3), base(), steps(days(100, 100, 100), days(100, 100, 100)), l2, new FakeBank());
            List<String> lines = Files.readAllLines(f2.toPath(), StandardCharsets.UTF_8);
            lines.remove(1);
            Files.write(f2.toPath(), lines, StandardCharsets.UTF_8);
            boolean caught2 = false;
            try { l2.records(); } catch (Ledger.TamperedException e) { caught2 = true; }
            check("удаление записи из середины обнаружено", caught2);
        }

        System.out.println("Сохранение настроек");
        {
            Commitment c = base().withChange(4000, 10000, D4);
            Commitment back = Commitment.parse(c.serialize());
            check("сериализация туда-обратно без потерь", back.serialize().equals(c.serialize())
                    && back.goalFor(D4.plusDays(7)) == 4000);
            boolean bad = false;
            try { Commitment.create(D1, 500, 100, "RUB", StepSource.HW); } catch (IllegalArgumentException e) { bad = true; }
            check("цель меньше минимума отклоняется", bad);
        }

        System.out.println("Форматирование для экрана");
        {
            check("штраф 500 ₽ выводится со знаком минус", Fmt.penalty(50000).equals("−500 ₽"));
            check("ноль выводится как 0 ₽", Fmt.penalty(0).equals("0 ₽"));
            check("копейки показываются, когда есть", Fmt.money(12050).equals("120.50") && Fmt.money(12000).equals("120"));
            check("числа с пробелом тысяч", Fmt.num(12345).equals("12 345") && Fmt.num(7).equals("7"));
        }

        System.out.println("Калории");
        {
            int k = Calories.netKcal(10000, 70, 175);
            check("10000 шагов, 70 кг, 175 см = около 254 ккал (чистый расход)", k >= 250 && k <= 258);
            check("длина шага по росту 175 см = 72.6 см", Math.abs(Calories.stepLengthM(175) - 0.72625) < 1e-9);
            check("расстояние 10000 шагов около 7.3 км", Math.abs(Calories.distanceKm(10000, 175) - 7.2625) < 1e-9);
            check("вес не указан: 0 ккал", Calories.netKcal(10000, 0, 175) == 0);
            check("рост не указан: 0 ккал", Calories.netKcal(10000, 70, 0) == 0);
            check("больше вес: больше калорий", Calories.netKcal(10000, 90, 175) > Calories.netKcal(10000, 70, 175));
        }

        System.out.println("Суточная норма (Plan)");
        {
            Plan p0 = Plan.compute(93, 179, 17, true, 500, 0);
            check("расход в покое для 93 кг, 179 см, 17 лет, парень = 1969", p0.ready && p0.bmr == 1969);
            check("суточный расход (x1.3) около 2559", Math.abs(p0.tdee - 2559) <= 1);
            check("норма при дефиците 500 около 2059", Math.abs(p0.norm - 2059) <= 1);
            check("без шагов бюджет равен норме", p0.bonus == 0 && p0.budget == p0.norm);
            Plan p3 = Plan.compute(93, 179, 17, true, 500, 3500);
            check("базовые 3500 шагов в бюджет не добавляются", p3.bonus == 0);
            Plan p10 = Plan.compute(93, 179, 17, true, 500, 10000);
            check("10000 шагов добавляют около 224 ккал (6500 шагов сверх базы)", p10.bonus >= 220 && p10.bonus <= 228);
            check("бюджет = норма + добавка", p10.budget == p10.norm + p10.bonus);
            check("белок 1.3 г/кг = 121 г", p0.proteinG == 121);
            check("БЖУ сходятся с бюджетом (±15 ккал)",
                    Math.abs(p10.proteinG * 4 + p10.fatG * 9 + p10.carbsG * 4 - p10.budget) <= 15);
            Plan small = Plan.compute(50, 160, 17, true, 600, 0);
            check("до 18 лет норма не ниже 2000 ккал", small.norm == 2000);
            Plan big = Plan.compute(93, 179, 17, true, 5000, 0);
            check("до 18 лет дефицит ограничен 600: норма не ниже tdee-600", big.norm >= big.tdee - 600 - 1);
            Plan adultF = Plan.compute(50, 160, 30, false, 1000, 0);
            check("взрослая женщина: минимум 1200", adultF.norm >= 1200);
            check("профиль не заполнен: план не готов", !Plan.compute(0, 179, 17, true, 500, 0).ready
                    && !Plan.compute(93, 179, 0, true, 500, 0).ready);
            check("пол влияет: у девушки норма ниже",
                    Plan.compute(70, 170, 25, false, 0, 0).bmr < Plan.compute(70, 170, 25, true, 0, 0).bmr);
        }

        System.out.println("Журнал еды (FoodBook)");
        {
            File dir = java.nio.file.Files.createTempDirectory("food").toFile();
            dir.deleteOnExit();
            FoodBook fb = new FoodBook(dir);
            fb.add("2026-11-04", "Овсянка", 350, 12, 6, 60);
            fb.add("2026-11-04", "Курица | гречка", 520, 45, 10, 60); // знак | в названии не должен ломать файл
            fb.add("2026-11-05", "Йогурт", 120, 8, 3, 14);
            List<FoodBook.Entry> day4 = fb.entries("2026-11-04");
            check("записи группируются по дням", day4.size() == 2 && fb.entries("2026-11-05").size() == 1);
            int[] t = FoodBook.totals(day4);
            check("суммы КБЖУ за день: 870 ккал, Б57 Ж16 У120", t[0] == 870 && t[1] == 57 && t[2] == 16 && t[3] == 120);
            check("знак | в названии заменён, запись цела", day4.get(1).name.equals("Курица гречка") && day4.get(1).kcal == 520);
            fb.remove(day4.get(0).idx);
            check("удаление записи работает и не трогает чужие дни",
                    fb.entries("2026-11-04").size() == 1 && fb.entries("2026-11-05").size() == 1
                            && fb.entries("2026-11-04").get(0).name.startsWith("Курица"));
            check("пустой день даёт нулевые суммы", FoodBook.totals(fb.entries("2030-01-01"))[0] == 0);

            fb.saveDish("Борщ", 250, 10, 12, 25);
            fb.saveDish("борщ", 260, 11, 12, 26); // то же название: заменяет
            fb.saveDish("Рис", 200, 4, 1, 44);
            List<FoodBook.Entry> dishes = fb.dishes();
            check("блюдо с тем же названием заменяется, а не дублируется", dishes.size() == 2);
            FoodBook.Entry borsch = null;
            for (FoodBook.Entry d : dishes) if (d.name.equalsIgnoreCase("борщ")) borsch = d;
            check("в списке актуальная версия блюда", borsch != null && borsch.kcal == 260);
            FoodBook.Entry s = borsch.scaled(1.5);
            check("1.5 порции умножают КБЖУ и подписываются", s.kcal == 390 && s.name.endsWith("×1.5"));
            check("2 порции подписываются без дроби", borsch.scaled(2).name.endsWith("×2"));
            fb.removeDish(borsch.idx);
            check("удаление блюда из списка", fb.dishes().size() == 1 && fb.dishes().get(0).name.equals("Рис"));
        }

        System.out.println();
        System.out.println("Пройдено: " + passed + ", провалено: " + failed);
        if (failed > 0) System.exit(1);
    }
}
