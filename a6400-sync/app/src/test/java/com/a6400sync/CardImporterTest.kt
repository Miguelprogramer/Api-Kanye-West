package com.a6400sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

class CardImporterTest {
    @Test fun soFotos_videosFicamDeFora() {
        listOf("DSC01234.JPG", "dsc01234.jpg", "DSC01234.ARW", "foto.jpeg").forEach { assertTrue(it, CardImporter.isPhoto(it)) }
        listOf("C0001.MP4", "00001.MTS", "C0001M01.XML", "DSC01234.THM", ".DSC01234.JPG", "SEMEXTENSAO", "INDEX.BDM").forEach {
            assertFalse(it, CardImporter.isPhoto(it))
        }
    }

    @Test fun ordem_maisAntigasPrimeiro() {
        fun p(n: String, t: Long) = CardImporter.CardPhoto("U:DCIM/100MSDCF/$n", null, n, 10, t)
        val o = CardImporter.order(listOf(p("DSC3.JPG", 300), p("DSC1.JPG", 100), p("DSC2.ARW", 200), p("DSC2.JPG", 200)))
        assertEquals(listOf("DSC1.JPG", "DSC2.ARW", "DSC2.JPG", "DSC3.JPG"), o.map { it.name })
    }

    @Test fun inicioDoDia() {
        val c = Calendar.getInstance().apply { set(2026, 10, 20, 17, 45, 12) }
        val s = Calendar.getInstance().apply { timeInMillis = CardImporter.startOfToday(c.timeInMillis) }
        assertEquals(20, s.get(Calendar.DAY_OF_MONTH)); assertEquals(0, s.get(Calendar.HOUR_OF_DAY)); assertEquals(0, s.get(Calendar.MINUTE))
    }

    @Test fun lembraImportadas_eSobreviveAoReinicio() {
        val f = File.createTempFile("importadas", ".txt"); f.delete()
        val k1 = CardImporter.key("1234-ABCD:DCIM/100MSDCF/DSC00001.JPG", 5_000_000, 1000)
        val k2 = CardImporter.key("1234-ABCD:DCIM/100MSDCF/DSC00002.JPG", 5_100_000, 2000)
        val a = ImportedStore(f)
        assertFalse(a.knowsCard("1234-ABCD"))
        a.add(k1); a.addAll(listOf(k1, k2))
        val b = ImportedStore(f)    // "reabre o app"
        assertTrue(b.contains(k1)); assertTrue(b.contains(k2))
        assertTrue(b.knowsCard("1234-ABCD")); assertFalse(b.knowsCard("9999-0000"))
        assertEquals(2, f.readLines().size)
        // mesma numeração depois de formatar o cartão (tamanho/data diferentes) = foto nova
        assertFalse(b.contains(CardImporter.key("1234-ABCD:DCIM/100MSDCF/DSC00001.JPG", 4_900_000, 9000)))
    }
}
