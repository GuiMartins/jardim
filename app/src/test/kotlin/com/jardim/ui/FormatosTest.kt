package com.jardim.ui

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatosTest {

    @Test
    fun `datas em português`() {
        assertEquals("Quinta-feira, 24 de setembro", dataLonga(LocalDate.of(2026, 9, 24)))
        assertEquals("1 de outubro de 2026", dataCompleta(LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun `meses de plantio do girassol, sem o ponto da abreviação`() {
        assertEquals("set, out, nov, dez, jan, fev", meses(listOf(9, 10, 11, 12, 1, 2)))
    }

    @Test
    fun `atraso no singular e no plural`() {
        assertEquals("Para hoje", atraso(0))
        assertEquals("Atrasada há 1 dia", atraso(1))
        assertEquals("Atrasada há 5 dias", atraso(5))
    }

    @Test
    fun `números sem casa decimal à toa, com vírgula quando precisa`() {
        assertEquals("12", numero(12.0))
        assertEquals("4,5", numero(4.5))
    }

    @Test
    fun `data do DatePicker vai e volta sem perder um dia`() {
        val data = LocalDate.of(2026, 10, 1)
        assertEquals(data, data.emMillisUtc().comoDataUtc())
    }
}
