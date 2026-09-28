/* TestOnly 注解 shim（原版在 utils/platform）。 */
@file:Suppress("unused")
package me.him188.ani.utils.platform.annotations

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class TestOnly
