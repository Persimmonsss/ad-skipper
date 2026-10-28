package com.example.hongguo_adskipper

/** aid(av 号) → BV 号，B站标准可逆编码，纯本地计算 */
object AvBv {
    private const val BASE = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
    private const val XOR_CODE = 23442827791579L
    private const val MAX_AID = 1L shl 51

    fun av2bv(aid: Long): String {
        val bytes = "BV1000000000".toCharArray()
        var tmp = (MAX_AID or aid) xor XOR_CODE
        var idx = bytes.size - 1
        while (tmp > 0) {
            bytes[idx] = BASE[(tmp % 58).toInt()]
            tmp /= 58
            idx--
        }
        val c3 = bytes[3]; bytes[3] = bytes[9]; bytes[9] = c3
        val c4 = bytes[4]; bytes[4] = bytes[7]; bytes[7] = c4
        return String(bytes)
    }
}
