/*
 * Copyright 2025 The Android Open Source Project
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

@file:Suppress("EXTENSION_SHADOWED_BY_MEMBER", "NOTHING_TO_INLINE")

package androidx.compose.runtime

import kotlin.text.toString as stdlibToString

/**
 * The return type of [currentCompositeKeyHashCode]. On most platforms this is a [Long] but may be a
 * different type if the platform target does not support [Long] efficiently (such as JavaScript).
 *
 * A `CompositeKeyHashCode` is a hash that correlates to a location in a Composition. Hashes are
 * stable, meaning that if the same composition hierarchy is recomposed or recreated, it will have
 * the same hashes. Hashes are very likely, but not guaranteed, to be unique.
 *
 * If you need to convert this value to an Int, it is strongly recommended to use [hashCode] instead
 * of [toInt][Number.toInt]. Truncating this value instead of hashing it can greatly impact a
 * value's effectiveness as a hash.
 *
 * @see currentCompositeKeyHashCode
 */
typealias CompositeKeyHashCode = Long

/**
 * Converts a [CompositeKeyHashCode] to a 64-bit Long. This may be higher precision than the
 * underlying type.
 */
inline fun CompositeKeyHashCode.toLong(): CompositeKeyHashCode = this

/**
 * Returns a String representation of a [CompositeKeyHashCode] with the specified [radix].
 *
 * @throws IllegalArgumentException when [radix] is not a valid radix for number to string
 *   conversion.
 */
fun CompositeKeyHashCode.toString(radix: Int): String = this.stdlibToString(radix)

inline fun CompositeKeyHashCode(initial: Int): CompositeKeyHashCode = initial.toLong()

/**
 * This function implements hashing arithmetic used by the Composer.
 *
 * Defines the top-down incremental hash. This hash is computed by taking the receiver hash,
 * combining it with the incoming [segment], and applying a [shift]. The order of these operations
 * and the application of these operations are arbitrary, but must be consistent with the other
 * functions in this file.
 *
 * The standard implementation of this is `(this rol shift) xor segment`.
 *
 *
 * 이 함수는 Composer에서 사용하는 해싱 연산을 구현합니다.
 *
 * 위에서 아래로 누적되는 해시를 정의합니다. 이 해시는 수신 해시에 [segment]를 결합하고 [shift]를
 * 적용하여 계산합니다. 이러한 연산의 순서와 적용 방식은 임의적이지만, 이 파일에 있는 다른 함수들과
 * 일관성을 유지해야 합니다.
 *
 * 표준 구현은 `(this rol shift) xor segment`입니다.
 */
internal fun CompositeKeyHashCode.compoundWith(
  segment: Int,
  shift: Int,
): CompositeKeyHashCode =
  (this rol shift) xor segment.toLong()

/**
 * This function implements hashing arithmetic used by the Composer.
 *
 * Performs the inverse operation of [compoundWith]. As in, the following equality always holds
 * true:
 * ```
 * key.compoundWith(segment, shift).unCompoundWith(segment, shift) == key
 * ```
 *
 * With the standard implementation of [compoundWith], this function should be implemented as `(this
 * xor segment) ror shift`
 */
internal fun CompositeKeyHashCode.unCompoundWith(
  segment: Int,
  shift: Int,
): CompositeKeyHashCode =
  (this xor segment.toLong()) ror shift

/**
 * This function implements hashing arithmetic used by the Composer.
 *
 * Implements [compoundWith] as a bottom-up hash. The sequence of the receiver and [segment]
 * argument are reversed in this role to build the hash from a child location upwards in the
 * composition hierarchy towards a known parent.
 *
 * The caller is responsible for specifying the [shift] value such that each segment has the
 * aggregated shift amount it would have by building the hash from top-down (which is the product of
 * its incremental shift amount and the distance of the compounded element from the child). [shift]
 * must be less than [CompositeKeyHashSizeBits].
 *
 * This implementation must be consistent with [compoundWith] such that building both hashes in
 * opposite directions always returns the same value. Given the standard implementation of
 * [compoundWith], this function should be implemented as: `this xor (segment rol shift)`
 */
internal fun CompositeKeyHashCode.bottomUpCompoundWith(
  segment: Int,
  shift: Int,
): CompositeKeyHashCode =
  this xor (segment.toLong() rol shift)

/**
 * This function implements hashing arithmetic used by the Composer.
 *
 * Implements [compoundWith] as a bottom-up hash. The sequence of the receiver and [segment]
 * argument are reversed in this role to build the hash from a child location upwards in the
 * composition hierarchy towards a known parent.
 *
 * The caller is responsible for specifying the [shift] value such that each segment has the
 * aggregated shift amount it would have by building the hash from top-down (which is the product of
 * its incremental shift amount and the distance of the compounded element from the child). [shift]
 * must be less than [CompositeKeyHashSizeBits].
 *
 * This implementation must be consistent with [compoundWith] such that building both hashes in
 * opposite directions always returns the same value. Given the standard implementation of
 * [compoundWith], this function should be implemented as: `this xor (segment rol shift)`
 *
 *
 * 이 함수는 Composer에서 사용하는 해싱 연산을 구현합니다.
 *
 * [compoundWith]를 하향식이 아닌 상향식 해시로 구현합니다. 이 과정에서는 수신자와 [segment] 인자의
 * 순서를 바꿔서, 컴포지션 계층에서 자식 위치로부터 알려진 부모 방향으로 해시를 구축합니다.
 *
 * 호출자는 [shift] 값을 지정해야 하며, 각 세그먼트가 상향식이 아니라 상위에서 하위로 해시를 만들었을
 * 때와 동일한 누적 시프트 값을 갖도록 해야 합니다. 이 값은 세그먼트의 증가 시프트 값과 자식으로부터
 * 결합된 요소까지의 거리의 곱입니다. [shift]는 [CompositeKeyHashSizeBits]보다 작아야 합니다.
 *
 * 이 구현은 [compoundWith]와 일관성을 유지해야 하며, 두 방향으로 해시를 구축했을 때 항상 동일한 값을
 * 반환해야 합니다. 표준 [compoundWith] 구현에 따르면 이 함수는 `this xor (segment rol shift)`로 구현됩니다.
 */
internal fun CompositeKeyHashCode.bottomUpCompoundWith(
  segment: CompositeKeyHashCode,
  shift: Int,
): CompositeKeyHashCode =
  this xor (segment rol shift)

/**
 * The number of bits available in a [CompositeKeyHashCode]. For most platforms, this is 64 since
 * the [CompositeKeyHashCode] is backed by a Long.
 */
internal const val CompositeKeyHashSizeBits: Int = 64

/**
 * An empty [CompositeKeyHashCode], equivalent to hashing no content. This is not necessarily the
 * same as the hash at the root of a composition. This is an effective default value in the absence
 * of a hash from [currentCompositeKeyHashCode].
 */
const val EmptyCompositeKeyHashCode: CompositeKeyHashCode = 0L
