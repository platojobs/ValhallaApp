package com.valhalla.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParamCatalogTest {

    private val interval = ParamCatalog.find(0x0001)!!
    private val mode = ParamCatalog.find(0x0002)!!
    private val sensitivity = ParamCatalog.find(0x0003)!!
    private val tag = ParamCatalog.find(0x0004)!!
    private val memory = ParamCatalog.find(0x0005)!!

    @Test
    fun `参数表条目按 id 唯一且全部标注需固件确认`() {
        assertEquals(5, ParamCatalog.params.size)
        assertEquals(
            ParamCatalog.params.size,
            ParamCatalog.params.map { it.id }.distinct().size,
        )
        assertTrue(ParamCatalog.params.none { it.firmwareConfirmed })
        assertNull(ParamCatalog.find(0x00FF))
    }

    @Test
    fun `整数参数在范围内编码为大端 u32`() {
        val valid = assertIs<ParamValidation.Valid>(ParamCatalog.validate(interval, "1000"))
        assertEquals("1000", valid.canonicalText)
        assertEquals(4, valid.bytes.size)
        assertEquals(1000, ParamCatalog.decodeInt(valid.bytes))
    }

    @Test
    fun `整数参数越界与非数字被拒绝`() {
        val low = assertIs<ParamValidation.Invalid>(ParamCatalog.validate(interval, "99"))
        assertTrue(low.message.contains("100"))
        val high = assertIs<ParamValidation.Invalid>(ParamCatalog.validate(sensitivity, "6"))
        val text = assertIs<ParamValidation.Invalid>(ParamCatalog.validate(interval, "abc"))
        assertTrue(text.message.contains("整数"))
    }

    @Test
    fun `枚举参数只接受取值表中的码`() {
        val valid = assertIs<ParamValidation.Valid>(ParamCatalog.validate(mode, "2"))
        assertEquals(1, valid.bytes.size)
        assertEquals(2, valid.bytes.first().toInt())
        assertIs<ParamValidation.Invalid>(ParamCatalog.validate(mode, "3"))
        assertIs<ParamValidation.Invalid>(ParamCatalog.validate(mode, "x"))
        assertEquals("采集", ParamCatalog.enumLabel(mode, 1))
    }

    @Test
    fun `只读参数一律拒绝写入`() {
        assertIs<ParamValidation.Invalid>(ParamCatalog.validate(tag, "ok"))
        assertIs<ParamValidation.Invalid>(ParamCatalog.validate(memory, "1"))
        assertFalse(tag.writable)
        assertFalse(memory.writable)
    }

    @Test
    fun `字符串参数按 UTF-8 字节长度校验`() {
        val writableTag = tag.copy(writable = true)
        val valid = assertIs<ParamValidation.Valid>(ParamCatalog.validate(writableTag, "valhalla"))
        assertEquals(8, valid.bytes.size)
        assertIs<ParamValidation.Invalid>(ParamCatalog.validate(writableTag, " "))
        // 16 个汉字 = 48 字节，超过 16 字节上限。
        val tooLong = assertIs<ParamValidation.Invalid>(
            ParamCatalog.validate(writableTag, "一二三四五六七八九十一二三四五六"),
        )
        assertTrue(tooLong.message.contains("上限"))
        assertIs<ParamValidation.Valid>(ParamCatalog.validate(writableTag, "一二三四五"))
    }

    @Test
    fun `设备原始值格式化覆盖整数枚举与字符串`() {
        assertEquals("1000", ParamCatalog.format(interval, ParamCatalog.encodeInt(1000)))
        assertEquals("2", ParamCatalog.format(mode, byteArrayOf(2)))
        assertEquals("valhalla", ParamCatalog.format(tag, "valhalla".encodeToByteArray()))
        // 超长原始值退化为十六进制，不抛异常。
        assertEquals("0102030405", ParamCatalog.format(interval, byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun `解码函数拒绝空值与超长输入`() {
        assertNull(ParamCatalog.decodeInt(ByteArray(0)))
        assertNull(ParamCatalog.decodeInt(ByteArray(5)))
        assertEquals(0x01020304, ParamCatalog.decodeInt(byteArrayOf(1, 2, 3, 4)))
    }
}
