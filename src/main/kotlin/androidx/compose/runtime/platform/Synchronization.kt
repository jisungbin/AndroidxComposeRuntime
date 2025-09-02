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

package androidx.compose.runtime.platform

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

internal typealias SynchronizedObject = Any

/**
 * Returns [ref] as a [SynchronizedObject] on platforms where [Any] is a valid [SynchronizedObject],
 * or a new [SynchronizedObject] instance if [ref] is null or this is not supported on the current
 * platform.
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun makeSynchronizedObject(ref: Any? = null): SynchronizedObject =
  ref ?: SynchronizedObject()

@OptIn(ExperimentalContracts::class)
@PublishedApi
internal inline fun <R> synchronized(lock: SynchronizedObject, block: () -> R): R {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  return kotlin.synchronized(lock = lock, block = block)
}
