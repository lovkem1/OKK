package com.OKK.yes.core.locator

import android.content.Context
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * 只靠"描述符"定位方法，不搜索任何日志字符串/特征字符串。
 *
 * 定位优先级：
 * 1) assets/descriptors.bin 中构建期固化的精确描述符（推荐，零运行时搜索）
 * 2) 仅当描述符表没有该 key 时，按稳定 API 签名在给定宿主类中匹配（不是字符串锚点）
 */
object MethodLocator {
    @Volatile
    private var table: TargetTable? = null

    fun install(context: Context) {
        if (table != null) return
        table = runCatching {
            context.assets.open("descriptors.bin").use { TargetTable.load(it) }
        }.onFailure {
            android.util.Log.w("Locator", "descriptors.bin unavailable: ${it.message}")
        }.getOrNull()
    }

    fun table(): TargetTable? = table

    /** 按 key 取构建期固化的 Method；失败请让功能禁用并上报，不要回退到字符串搜索。 */
    fun method(key: String, classLoader: ClassLoader): Method {
        val target = table()?.get(key)
            ?: error("no descriptor for key=$key; rebuild descriptors.bin")
        val (cls, name, sig) = splitDescriptor(target.descriptor)
        var clazz: Class<*> = classLoader.loadClass(cls)
        while (true) {
            clazz.declaredMethods.firstOrNull { it.name == name && typeSig(it) == sig }?.let {
                it.isAccessible = true
                return it
            }
            clazz = clazz.superclass ?: break
        }
        throw NoSuchMethodException(target.descriptor)
    }

    fun constructor(key: String, classLoader: ClassLoader): Constructor<*> {
        val target = table()?.get(key)
            ?: error("no descriptor for key=$key; rebuild descriptors.bin")
        val cls = splitDescriptor(target.descriptor).first
        val clazz = classLoader.loadClass(cls)
        return clazz.declaredConstructors.first { ctor ->
            buildString {
                append('(')
                ctor.parameterTypes.forEach { append(typeOf(it)) }
                append(")V")
            } == target.descriptor.substring(target.descriptor.indexOf('('))
        }.also { it.isAccessible = true }
    }

    /** 仅用类型签名定位：用于类名稳定、但方法名混淆的场景。 */
    fun methodBySignature(
        className: String,
        signature: String,
        classLoader: ClassLoader,
    ): Method? {
        val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return null
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.firstOrNull { typeSig(it) == signature }?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    private fun splitDescriptor(desc: String): Triple<String, String, String> {
        val arrow = desc.indexOf("->")
        val paren = desc.indexOf('(', arrow)
        require(arrow > 1 && paren > arrow) { "bad descriptor: $desc" }
        val cls = desc.substring(1, arrow - 1).replace('/', '.')
        return Triple(cls, desc.substring(arrow + 2, paren), desc.substring(paren))
    }

    private fun typeSig(m: Method): String = buildString {
        append('(')
        m.parameterTypes.forEach { append(typeOf(it)) }
        append(')')
        append(typeOf(m.returnType))
    }

    private fun typeOf(t: Class<*>): String = when {
        t == Void.TYPE -> "V"
        t == java.lang.Integer.TYPE -> "I"
        t == java.lang.Long.TYPE -> "J"
        t == java.lang.Boolean.TYPE -> "Z"
        t == java.lang.Byte.TYPE -> "B"
        t == java.lang.Short.TYPE -> "S"
        t == java.lang.Character.TYPE -> "C"
        t == java.lang.Float.TYPE -> "F"
        t == java.lang.Double.TYPE -> "D"
        t.isArray -> "[" + typeOf(t.componentType!!)
        else -> "L" + t.name.replace('.', '/') + ";"
    }
}
