/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.runtime.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import kotlinx.coroutines.CancellationException

/**
 * Returns the hash code for the given object that is unique across all currently allocated objects.
 * The hash code for the null reference is zero.
 *
 * Can be negative, and near Int.MAX_VALUE, so it can overflow if used as part of calculations. For
 * example, don't use this:
 * ```
 * val comparison = identityHashCode(midVal) - identityHashCode(leftVal)
 * if (comparison < 0) ...
 * ```
 *
 * Use this instead:
 * ```
 * if (identityHashCode(midVal) < identityHashCode(leftVal)) ...
 * ```
 */
internal fun identityHashCode(instance: Any?): Int = System.identityHashCode(instance)

internal fun invokeComposable(composer: Composer, composable: @Composable () -> Unit) {
  @Suppress("UNCHECKED_CAST") val realFn = composable as Function2<Composer, Int, Unit>
  realFn(composer, 1)
}

internal fun logError(message: String, e: Throwable) {
  println("[ERROR] $message $e")
}

/**
 * Represents a platform-optimized cancellation exception. This allows us to configure exceptions
 * separately on JVM and other platforms.
 */
internal abstract class PlatformOptimizedCancellationException(message: String? = null) :
  CancellationException(message) {
  override fun fillInStackTrace(): Throwable {
    // Avoid null.clone() on Android <= 6.0 when accessing stackTrace
    stackTrace = emptyArray()
    return this
  }
}
