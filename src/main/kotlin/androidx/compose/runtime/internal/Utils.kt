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

internal fun invokeComposable(composer: Composer, composable: @Composable () -> Unit) {
  @Suppress("UNCHECKED_CAST") val realFn = composable as Function2<Composer, Int, Unit>
  realFn(composer, 1)
}

internal fun logError(message: String, e: Throwable) {
  System.err.println(message)
  e.printStackTrace(System.err)
}
