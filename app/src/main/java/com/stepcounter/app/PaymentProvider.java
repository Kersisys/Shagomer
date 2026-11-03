package com.stepcounter.app;

/**
 * Перевод штрафа на счёт, откуда деньги нельзя забрать.
 *
 * Здесь граница между кодом и реальными деньгами. Всё остальное приложение работает
 * только через этот интерфейс, а реальный банк подключается отдельной реализацией.
 *
 * Требование к реализации: ИДЕМПОТЕНТНОСТЬ. Один и тот же idempotencyKey при повторном вызове
 * не должен списывать деньги второй раз. Приложение может повторять вызов после сбоя.
 *
 * Намеренно нет метода "вернуть / вывести": приложение не умеет забирать деньги назад.
 */
interface PaymentProvider {
    Result transfer(String idempotencyKey, long amountMinor, String currency);

    final class Result {
        final boolean ok;
        final String ref;    // идентификатор операции у провайдера
        final String error;  // причина, если не ok

        private Result(boolean ok, String ref, String error) {
            this.ok = ok;
            this.ref = ref;
            this.error = error;
        }

        static Result ok(String ref) { return new Result(true, ref, null); }
        static Result fail(String error) { return new Result(false, null, error); }
    }
}
