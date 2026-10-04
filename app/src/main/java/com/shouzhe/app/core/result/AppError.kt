package com.shouzhe.app.core.result

/**
 * 统一错误模型。
 * ModelGateway 不向上抛异常，一律转成 AppError。
 */
sealed class AppError(val message: String) {
    /** 无网 / 超时 */
    data class Network(val detail: String) : AppError(detail)

    /** Key 无效 / 余额不足 */
    data class Auth(val detail: String) : AppError(detail)

    /** 限流 */
    data class RateLimit(val retryAfterMs: Long?, val detail: String) : AppError(detail)

    /** 模型输出无法解析 */
    data class BadOutput(val raw: String) : AppError("模型没看懂")

    /** 供应商返回错误 */
    data class Provider(val code: Int?, val detail: String) : AppError(detail)

    /** 正文抽取失败 */
    data class ExtractFailed(val url: String, val detail: String) : AppError(detail)

    data class Unknown(val detail: String) : AppError(detail)

    /** 给用户看的一句话 —— 说清怎么办，不道歉、不含糊 */
    fun userHint(): String = when (this) {
        is Auth -> "API Key 无效或余额不足，请到设置里检查"
        is RateLimit -> "请求太频繁，稍等一会儿再试"
        is Network -> "网络不通，已经先存下来了，联网后自动重试"
        is BadOutput -> "模型没看懂，可以换个模型试试"
        is ExtractFailed -> "这篇没抓到正文，只存了链接"
        is Provider -> "模型服务出错：$detail"
        is Unknown -> "出了点问题：$detail"
    }
}

/** 轻量 Result —— 避免与 Kotlin stdlib 的 Result 混淆 */
sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Err(val error: AppError) : Outcome<Nothing>()

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): AppError? = (this as? Err)?.error
    val isOk: Boolean get() = this is Ok
}

inline fun <T, R> Outcome<T>.map(f: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(f(value))
    is Outcome.Err -> this
}

inline fun <T> Outcome<T>.onOk(f: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Ok) f(value)
    return this
}

inline fun <T> Outcome<T>.onErr(f: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Err) f(error)
    return this
}

fun <T> T.ok(): Outcome<T> = Outcome.Ok(this)
fun AppError.err(): Outcome<Nothing> = Outcome.Err(this)