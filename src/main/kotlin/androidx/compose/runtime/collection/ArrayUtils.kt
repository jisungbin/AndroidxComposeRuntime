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

package androidx.compose.runtime.collection

/**
 * Equivalent of Array.copyInto() with an implementation designed to avoid
 * unnecessary null checks and exception throws on Android after inlining.
 *
 * Array.copyInto()와 동등하며, 인라인 이후 Android에서 불필요한 null 검사와
 * 예외 발생을 피하도록 설계된 구현입니다.
 */
internal fun <T> Array<out T>.fastCopyInto(
  destination: Array<T>,
  destinationOffset: Int,
  startIndex: Int,
  endIndex: Int,
): Array<T> =
  this.copyInto(
    destination = destination,
    destinationOffset = destinationOffset,
    startIndex = startIndex,
    endIndex = endIndex,
  )
