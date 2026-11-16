package com.gakiji.aiaccount;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class AccountMatchTest {
    private static Ledger.Account account(long id, String name, String aliases) {
        return new Ledger.Account(id, name, 0, 0, aliases);
    }

    @Test public void picksUniqueAliasMatch() {
        var accounts = List.of(account(1, "支付宝", "网商"), account(2, "微信", "零钱"),
                account(3, "中国银行", ""), account(4, "亲属卡", "亲情卡"));
        assertEquals(1, Ledger.bestAccount(accounts, "网商银行储蓄卡(6627)"));
        assertEquals(2, Ledger.bestAccount(accounts, "零钱"));
        assertEquals(4, Ledger.bestAccount(accounts, "亲情卡(良辉(刘良辉))"));
        assertEquals(4, Ledger.bestAccount(accounts, "亲属卡(明哥)"));
        assertEquals(0, Ledger.bestAccount(accounts, "招商银行储蓄卡(1234)"));
    }

    @Test public void exactAliasBeatsNickname() {
        var accounts = List.of(account(1, "支付宝", "网商"), account(2, "网商卡", "网商银行储蓄卡(6627)"));
        assertEquals(2, Ledger.bestAccount(accounts, "网商银行储蓄卡(6627)"));
    }

    @Test public void leavesTieEmpty() {
        var accounts = List.of(account(1, "网商一", "网商"), account(2, "网商二", "网商银行"));
        assertEquals(0, Ledger.bestAccount(accounts, "网商银行储蓄卡(6627)"));
    }
}
