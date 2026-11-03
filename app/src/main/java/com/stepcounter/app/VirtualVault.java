package com.stepcounter.app;

/**
 * ЗАГЛУШКА. Реальные деньги не двигает: просто подтверждает перевод, а запись о нём
 * остаётся в журнале. Подходит для проверки всей логики без банка.
 *
 * Чтобы штрафы стали настоящими, нужна другая реализация PaymentProvider
 * (банк, платёжный сервис, благотворительность), см. README.
 */
final class VirtualVault implements PaymentProvider {
    @Override
    public Result transfer(String idempotencyKey, long amountMinor, String currency) {
        return Result.ok("virtual-" + idempotencyKey);
    }
}
