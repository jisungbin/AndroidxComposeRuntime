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

import java.util.concurrent.atomic.AtomicInteger

internal typealias AtomicReference<V> = java.util.concurrent.atomic.AtomicReference<V>

internal class AtomicInt(value: Int) : AtomicInteger(value) {
  fun add(amount: Int): Int = addAndGet(amount)

  // These are implemented by Number, but Kotlin fails to resolve them.
  override fun toByte(): Byte = toInt().toByte()

  override fun toShort(): Short = toInt().toShort()

  @Deprecated(
    "Direct conversion to Char is deprecated. Use toInt().toChar() or Char " +
      "constructor instead.\nIf you override toChar() function in your Number inheritor, " +
      "it's recommended to gradually deprecate the overriding function and then " +
      "remove it.\nSee https://youtrack.jetbrains.com/issue/KT-46465 for details about " +
      "the migration",
    replaceWith = ReplaceWith("this.toInt().toChar()"),
  )
  override fun toChar(): Char = toInt().toChar()
}

@JvmInline
internal value class AtomicBoolean(private val wrapped: AtomicInt = AtomicInt(0)) {
  constructor(value: Boolean) : this(AtomicInt(if (value) 1 else 0))

  fun get(): Boolean = wrapped.get() != 0

  fun set(value: Boolean) = wrapped.set(if (value) 1 else 0)

  fun getAndSet(newValue: Boolean): Boolean = wrapped.compareAndSet(1, if (newValue) 1 else 0)
}
