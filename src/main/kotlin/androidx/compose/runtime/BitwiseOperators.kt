/*
 * Copyright 2020 The Android Open Source Project
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

@file:Suppress("NOTHING_TO_INLINE")

package androidx.compose.runtime

// NOTE: rotateRight, marked @ExperimentalStdlibApi is also marked inline-only,
//  which makes this usage stable.
internal inline infix fun Int.ror(other: Int) = this.rotateRight(other)

// NOTE: rotateRight, marked @ExperimentalStdlibApi is also marked inline-only,
//  which makes this usage stable.
internal inline infix fun Long.ror(other: Int) = this.rotateRight(other)

// NOTE: rotateLeft, marked @ExperimentalStdlibApi is also marked inline-only,
//  which makes this usage stable.
internal inline infix fun Int.rol(other: Int) = this.rotateLeft(other)

// NOTE: rotateLeft, marked @ExperimentalStdlibApi is also marked inline-only,
//  which makes this usage stable.
internal inline infix fun Long.rol(other: Int) = this.rotateLeft(other)


// 이 [Long] 숫자의 이진 표현을 지정된 [bitCount] 비트만큼 오른쪽으로 회전합니다.
// 오른쪽에서 밀려난 최하위 비트들은 다시 왼쪽의 최상위 비트로 들어옵니다.
//
// 비트 수를 음수로 지정해 오른쪽으로 회전하면, 그 수의 부호를 반전시켜 왼쪽으로
// 회전하는 것과 같습니다: `number.rotateRight(-n) == number.rotateLeft(n)`
//
// [Long.SIZE_BITS] (64)의 배수만큼 회전하면 같은 숫자가 반환되며, 일반적으로는
// 다음과 같습니다: `number.rotateRight(n) == number.rotateRight(n % 64)`
//
// public actual inline fun Long.rotateRight(bitCount: Int): Long =
//   java.lang.Long.rotateRight(i = this, distance = bitCount)


// 이 [Long] 숫자의 이진 표현을 지정된 [bitCount]만큼 왼쪽으로 회전시킵니다.
// 왼쪽에서 밀려난 최상위 비트들은 오른쪽의 최하위 비트로 다시 들어옵니다.
//
// 음수 [bitCount]만큼 왼쪽으로 회전시키는 것은, 해당 수를 양수로 바꾼 뒤
// 오른쪽으로 회전시키는 것과 동일합니다: `number.rotateLeft(-n) == number.rotateRight(n)`
//
// [Long.SIZE_BITS] (64)의 배수만큼 회전시키는 경우 동일한 수가 반환됩니다.
// 좀 더 일반적으로는 다음과 같습니다: `number.rotateLeft(n) == number.rotateLeft(n % 64)`
//
// public actual inline fun Long.rotateLeft(bitCount: Int): Long =
//   java.lang.Long.rotateLeft(i = this, distance = bitCount)
