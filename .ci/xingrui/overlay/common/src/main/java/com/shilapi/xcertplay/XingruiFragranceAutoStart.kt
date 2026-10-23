package com.shilapi.xcertplay

import android.content.Context
import android.util.Log

/**
 * Turns the Xingrui E02 fragrance system on through the same ECarX CarFunction API used by the
 * factory HVAC application.
 *
 * The verified E02 contract is:
 *   Car.create(Context) -> ICar.getICarFunction()
 *   ICarFunction.getFunctionValue(0x100B0100, 0)
 *   ICarFunction.setFunctionValue(0x100B0100, 0, 1)
 *
 * The factory HvacModule maps function value 1 to vehicle property 0x2E700120 value 2. Keep the
 * write at the CarFunction layer instead of writing the byte-array VHAL property directly.
 */
object XingruiFragranceAutoStart {
    private const val TAG = "XingruiFragrance"
    private const val OPEN_API_PACKAGE = "com.ecarx.sdk.openapi"
    private const val CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car"
    private const val CAR_FUNCTION_CLASS = "com.ecarx.xui.adaptapi.car.base.ICarFunction"
    private const val FUNCTION_FRAGRANCE_SWITCH = 0x100B0100
    private const val ZONE_GLOBAL = 0
    private const val VALUE_ON = 1
    private const val MAX_ATTEMPTS = 5
    private const val RETRY_DELAY_MS = 1_500L

    fun ensureOnAsync(context: Context, onFinished: () -> Unit = {}) {
        val app = context.applicationContext
        Thread({
            try {
                for (attempt in 1..MAX_ATTEMPTS) {
                    when (ensureOn(app, attempt)) {
                        Result.ALREADY_ON, Result.ENABLED -> return@Thread
                        Result.RETRY -> if (attempt < MAX_ATTEMPTS) {
                            Thread.sleep(RETRY_DELAY_MS)
                        }
                    }
                }
                Log.w(TAG, "fragrance auto-start gave up after $MAX_ATTEMPTS attempts")
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "fragrance auto-start interrupted")
            } finally {
                runCatching(onFinished).onFailure { Log.w(TAG, "finish callback failed", it) }
            }
        }, "xingrui-fragrance-start").apply {
            isDaemon = false
            start()
        }
    }

    private fun ensureOn(context: Context, attempt: Int): Result {
        try {
            val carClass = loadOpenApiClass(context, CAR_CLASS)
            val car = carClass.getMethod("create", Context::class.java).invoke(null, context)
                ?: error("Car.create returned null")
            val carFunction = car.javaClass.getMethod("getICarFunction").invoke(car)
                ?: error("getICarFunction returned null")
            val api = runCatching {
                Class.forName(CAR_FUNCTION_CLASS, true, carClass.classLoader)
            }.getOrElse {
                loadOpenApiClass(context, CAR_FUNCTION_CLASS)
            }
            val intType = Int::class.javaPrimitiveType ?: error("primitive int type unavailable")
            val getValue = api.getMethod("getFunctionValue", intType, intType)
            val setValue = api.getMethod("setFunctionValue", intType, intType, intType)

            val before = (getValue.invoke(
                carFunction,
                FUNCTION_FRAGRANCE_SWITCH,
                ZONE_GLOBAL,
            ) as Number).toInt()
            if (before == VALUE_ON) {
                Log.i(TAG, "auto-start attempt=$attempt: fragrance already on")
                return Result.ALREADY_ON
            }

            val accepted = setValue.invoke(
                carFunction,
                FUNCTION_FRAGRANCE_SWITCH,
                ZONE_GLOBAL,
                VALUE_ON,
            ) as? Boolean ?: false
            if (!accepted) {
                Log.w(TAG, "auto-start attempt=$attempt: CarFunction rejected fragrance on request; before=$before")
                return Result.RETRY
            }

            val after = runCatching {
                (getValue.invoke(
                    carFunction,
                    FUNCTION_FRAGRANCE_SWITCH,
                    ZONE_GLOBAL,
                ) as Number).toInt()
            }.getOrNull()
            Log.i(TAG, "auto-start attempt=$attempt: request accepted; before=$before after=$after")
            return Result.ENABLED
        } catch (error: Throwable) {
            val root = generateSequence(error) { it.cause }.last()
            Log.w(TAG, "auto-start attempt=$attempt failed: ${root.javaClass.simpleName}: ${root.message}")
            return Result.RETRY
        }
    }

    private fun loadOpenApiClass(context: Context, className: String): Class<*> =
        runCatching { Class.forName(className) }.getOrElse {
            val sdkContext = context.createPackageContext(
                OPEN_API_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
            Class.forName(className, true, sdkContext.classLoader)
        }

    private enum class Result {
        ALREADY_ON,
        ENABLED,
        RETRY,
    }
}
