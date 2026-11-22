package com.OKK.yes.core.locator

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * 构建期生成的"目标方法描述符表"。
 *
 * 运行时**不携带**任何日志字符串 / DexKit 字符串锚点，只读取本表里的
 * "Lcom/xxx;->method(Sig)V" 形式描述符。微信升级后重新运行
 * tools/locator/build_descriptors.py 覆盖 assets/descriptors.bin 即可。
 */
data class LocatorTarget(
    val key: String,
    val descriptor: String,
    val codeHash: Int,
)

class TargetTable private constructor(
    private val version: String,
    targets: List<LocatorTarget>,
) {
    private val byKey: Map<String, LocatorTarget> = targets.associateBy { it.key }

    operator fun get(key: String): LocatorTarget? = byKey[key]

    fun require(key: String): LocatorTarget =
        byKey[key] ?: error("target descriptor missing: $key (rebuild descriptors.bin)")

    fun keys(): Set<String> = byKey.keys

    companion object {
        private const val MAGIC = 0x444B4B4F // "OKKD" little endian

        /** 与 Python pack_binary 完全一致的滚动 XOR 解密。 */
        private fun decrypt(payload: ByteArray, version: String): ByteArray {
            val crc = CRC32()
            crc.update(version.toByteArray(StandardCharsets.UTF_8))
            val seed = (crc.value.toInt() xor 0xA5A5A5A5) and 0xFF
            val out = ByteArray(payload.size)
            for (i in payload.indices) {
                out[i] = (payload[i].toInt() xor ((seed + i * 31) and 0xFF)).toByte()
            }
            return out
        }

        fun load(input: InputStream): TargetTable {
            val all = input.readBytes()
            require(all.size >= 8) { "descriptors.bin too short" }
            val magic = le32(all, 0)
            require(magic == MAGIC) { "bad descriptors.bin magic" }
            val format = le16(all, 4)
            require(format == 1) { "unsupported descriptors.bin format: $format" }
            val count = le16(all, 6)

            // 版本串长度与内容保持明文（用于派生解密种子），记录区才做滚动 XOR
            val versionLen = (all[8].toInt() and 0xFF).coerceAtMost(64)
            val versionBytes = ByteArray(versionLen) { all[9 + it] }
            val version = String(versionBytes, StandardCharsets.UTF_8)
            val decrypted = decrypt(all.copyOfRange(9 + versionLen, all.size), version)

            var pos = 0
            val targets = ArrayList<LocatorTarget>(count)
            repeat(count) {
                val keyLen = u8(decrypted, pos)
                pos++
                val key = String(decrypted, pos, keyLen, StandardCharsets.UTF_8)
                pos += keyLen
                val descLen = le16(decrypted, pos)
                pos += 2
                val desc = String(decrypted, pos, descLen, StandardCharsets.UTF_8)
                pos += descLen
                val hash = le32(decrypted, pos)
                pos += 4
                targets += LocatorTarget(key, desc, hash)
            }
            return TargetTable(version, targets)
        }

        private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
        private fun le16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
        private fun le32(b: ByteArray, i: Int): Int =
            u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)
    }
}
