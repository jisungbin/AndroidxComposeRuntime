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

@file:OptIn(InternalComposeApi::class)
@file:Suppress("EXPECT_ACTUAL_INCOMPATIBILITY") // b/418285824

package androidx.compose.runtime.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeCompilerApi
import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.RecomposeScopeImpl
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rol
import androidx.compose.runtime.updateChangedFlags

private const val SLOTS_COUNT_PER_INT = 10
private const val BITS_COUNT_PER_SLOT = 3

internal fun bitsForSlot(bits: Int, slot: Int): Int {
  val realSlot = slot % SLOTS_COUNT_PER_INT
  return bits shl (realSlot * BITS_COUNT_PER_SLOT + 1)
}

// ParamState.Same(0b001)
internal fun sameBits(slot: Int): Int = bitsForSlot(bits = 0b001, slot = slot)

// ParamState.Different(0b010)
internal fun differentBits(slot: Int): Int = bitsForSlot(bits = 0b010, slot = slot)

/**
 * A Restart is created to hold composable lambdas to track when they are invoked allowing
 * the invocations to be invalidated when a new composable lambda is created during composition.
 *
 * This allows much of the call-graph to be skipped when a composable function is passed
 * through multiple levels of composable functions.
 *
 *
 * Restart는 컴포저블 람다가 호출될 때 이를 추적하기 위해 생성되며, 컴포지션 중 새로운
 * 컴포저블 람다가 생성되면 기존 호출들을 무효화할 수 있게 합니다.
 *
 * 이를 통해 컴포저블 함수가 여러 단계의 컴포저블 함수를 거쳐 전달되더라도 호출 그래프의
 * 많은 부분을 건너뛸 수 있습니다.
 */
@Suppress("UNCHECKED_CAST")
@Stable
internal class ComposableLambdaImpl(
  val key: Int,
  private val tracked: Boolean, // RecomposeScope 변경 추적 및 block 변경시 리컴포지션 진행 여부
  block: Any?,
) : ComposableLambda {
  private var _block: Any? = block
  private var scope: RecomposeScope? = null
  private var scopes: MutableList<RecomposeScope>? = null

  // tracked == true 라면 scope, scopes를 모두 invalidate() 하는 함수
  // update(block)에서 block이 변경될 때만 호출됨
  private fun trackWrite() {
    if (tracked) {
      val scope = this.scope
      if (scope != null) {
        scope.invalidate()
        this.scope = null
      }

      val scopes = this.scopes
      if (scopes != null) {
        for (index in 0 until scopes.size) {
          val item = scopes[index]
          item.invalidate()
        }
        scopes.clear()
      }
    }
  }

  private fun trackRead(composer: Composer) {
    if (tracked) {
      val scope = composer.recomposeScope
      if (scope != null) {
        // Find the first invalid scope and replace it or record it if
        // no scopes are invalid.
        //
        // 첫 번째로 무효화된 스코프를 찾아 교체하거나, 무효화된 스코프가
        // 없으면 기록합니다.
        composer.recordUsed(scope = scope)

        val lastScope = this.scope
        if (lastScope.replacableWith(other = scope)) {
          // 무효화된 scope 찾아 교체하기
          this.scope = scope
        } else {
          val lastScopes = scopes
          if (lastScopes == null) {
            scopes = mutableListOf(scope)
          } else {
            for (index in 0 until lastScopes.size) {
              val scopeAtIndex = lastScopes[index]
              if (scopeAtIndex.replacableWith(other = scope)) {
                lastScopes[index] = scope
                return
              }
            }

            lastScopes.add(scope)
          }
        }
      }
    }
  }

  fun update(block: Any) {
    if (_block != block) {
      val oldBlockNull = _block == null
      _block = block
      if (!oldBlockNull) {
        trackWrite()
      }
    }
  }

  override operator fun invoke(c: Composer, changed: Int): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed or if (c.changed(this)) differentBits(0) else sameBits(0)
    val result = (_block as (c: Composer, changed: Int) -> Any?)(c, dirty)
    c.endRestartGroup()?.updateScope(this::invoke)
    return result
  }

  override operator fun invoke(p1: Any?, c: Composer, changed: Int): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed or if (c.changed(this)) differentBits(1) else sameBits(1)
    val result = (_block as (p1: Any?, c: Composer, changed: Int) -> Any?)(p1, c, dirty)
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(p1, nc, updateChangedFlags(changed) or 0b1)
    }
    return result
  }

  override operator fun invoke(p1: Any?, p2: Any?, c: Composer, changed: Int): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed or if (c.changed(this)) differentBits(2) else sameBits(2)
    val result =
      (_block as (p1: Any?, p2: Any?, c: Composer, changed: Int) -> Any?)(p1, p2, c, dirty)
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(p1, p2, nc, updateChangedFlags(changed) or 0b1)
    }
    return result
  }

  override operator fun invoke(p1: Any?, p2: Any?, p3: Any?, c: Composer, changed: Int): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed or if (c.changed(this)) differentBits(3) else sameBits(3)
    val result =
      (_block as (p1: Any?, p2: Any?, p3: Any?, c: Composer, changed: Int) -> Any?)(
        p1,
        p2,
        p3,
        c,
        dirty,
      )
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(p1, p2, p3, nc, updateChangedFlags(changed) or 0b1)
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    c: Composer,
    changed: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed or if (c.changed(this)) differentBits(4) else sameBits(4)
    val result =
      (_block as (p1: Any?, p2: Any?, p3: Any?, p4: Any?, c: Composer, changed: Int) -> Any?)(
        p1,
        p2,
        p3,
        p4,
        c,
        dirty,
      )
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(p1, p2, p3, p4, nc, updateChangedFlags(changed) or 0b1)
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    p5: Any?,
    c: Composer,
    changed: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed or if (c.changed(this)) differentBits(5) else sameBits(5)
    val result =
      (_block
        as
          (
        p1: Any?, p2: Any?, p3: Any?, p4: Any?, p5: Any?, c: Composer, changed: Int,
      ) -> Any?)(p1, p2, p3, p4, p5, c, dirty)
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(p1, p2, p3, p4, p5, nc, updateChangedFlags(changed) or 0b1)
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    p5: Any?,
    p6: Any?,
    c: Composer,
    changed: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed or if (c.changed(this)) differentBits(6) else sameBits(6)
    val result =
      (_block
        as
          (
        p1: Any?,
        p2: Any?,
        p3: Any?,
        p4: Any?,
        p5: Any?,
        p6: Any?,
        c: Composer,
        changed: Int,
      ) -> Any?)(p1, p2, p3, p4, p5, p6, c, dirty)
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(p1, p2, p3, p4, p5, p6, nc, updateChangedFlags(changed) or 0b1)
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    p5: Any?,
    p6: Any?,
    p7: Any?,
    c: Composer,
    changed: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed or if (c.changed(this)) differentBits(7) else sameBits(7)
    val result =
      (_block
        as
          (
        p1: Any?,
        p2: Any?,
        p3: Any?,
        p4: Any?,
        p5: Any?,
        p6: Any?,
        p7: Any?,
        c: Composer,
        changed: Int,
      ) -> Any?)(p1, p2, p3, p4, p5, p6, p7, c, dirty)
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(p1, p2, p3, p4, p5, p6, p7, nc, updateChangedFlags(changed) or 0b1)
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    p5: Any?,
    p6: Any?,
    p7: Any?,
    p8: Any?,
    c: Composer,
    changed: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed or if (c.changed(this)) differentBits(8) else sameBits(8)
    val result =
      (_block
        as
          (
        p1: Any?,
        p2: Any?,
        p3: Any?,
        p4: Any?,
        p5: Any?,
        p6: Any?,
        p7: Any?,
        p8: Any?,
        c: Composer,
        changed: Int,
      ) -> Any?)(p1, p2, p3, p4, p5, p6, p7, p8, c, dirty)
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(p1, p2, p3, p4, p5, p6, p7, p8, nc, updateChangedFlags(changed) or 0b1)
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    p5: Any?,
    p6: Any?,
    p7: Any?,
    p8: Any?,
    p9: Any?,
    c: Composer,
    changed: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed or if (c.changed(this)) differentBits(9) else sameBits(9)
    val result =
      (_block
        as
          (
        p1: Any?,
        p2: Any?,
        p3: Any?,
        p4: Any?,
        p5: Any?,
        p6: Any?,
        p7: Any?,
        p8: Any?,
        p9: Any?,
        c: Composer,
        changed: Int,
      ) -> Any?)(p1, p2, p3, p4, p5, p6, p7, p8, p9, c, dirty)
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(p1, p2, p3, p4, p5, p6, p7, p8, p9, nc, updateChangedFlags(changed) or 0b1)
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    p5: Any?,
    p6: Any?,
    p7: Any?,
    p8: Any?,
    p9: Any?,
    p10: Any?,
    c: Composer,
    changed: Int,
    changed1: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed1 or if (c.changed(this)) differentBits(10) else sameBits(10)
    val result =
      (_block
        as
          (
        p1: Any?,
        p2: Any?,
        p3: Any?,
        p4: Any?,
        p5: Any?,
        p6: Any?,
        p7: Any?,
        p8: Any?,
        p9: Any?,
        p10: Any?,
        c: Composer,
        changed: Int,
        changed1: Int,
      ) -> Any?)(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, c, changed, dirty)
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, nc, changed or 0b1, changed)
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    p5: Any?,
    p6: Any?,
    p7: Any?,
    p8: Any?,
    p9: Any?,
    p10: Any?,
    p11: Any?,
    c: Composer,
    changed: Int,
    changed1: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed1 or if (c.changed(this)) differentBits(11) else sameBits(11)
    val result =
      (_block
        as
          (
        p1: Any?,
        p2: Any?,
        p3: Any?,
        p4: Any?,
        p5: Any?,
        p6: Any?,
        p7: Any?,
        p8: Any?,
        p9: Any?,
        p10: Any?,
        p11: Any?,
        c: Composer,
        changed: Int,
        changed1: Int,
      ) -> Any?)(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, c, changed, dirty)
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(
        p1,
        p2,
        p3,
        p4,
        p5,
        p6,
        p7,
        p8,
        p9,
        p10,
        p11,
        nc,
        updateChangedFlags(changed) or 0b1,
        updateChangedFlags(changed1),
      )
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    p5: Any?,
    p6: Any?,
    p7: Any?,
    p8: Any?,
    p9: Any?,
    p10: Any?,
    p11: Any?,
    p12: Any?,
    c: Composer,
    changed: Int,
    changed1: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed1 or if (c.changed(this)) differentBits(12) else sameBits(12)
    val result =
      (_block
        as
          (
        p1: Any?,
        p2: Any?,
        p3: Any?,
        p4: Any?,
        p5: Any?,
        p6: Any?,
        p7: Any?,
        p8: Any?,
        p9: Any?,
        p10: Any?,
        p11: Any?,
        p12: Any?,
        c: Composer,
        changed: Int,
        changed1: Int,
      ) -> Any?)(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, c, changed, dirty)
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(
        p1,
        p2,
        p3,
        p4,
        p5,
        p6,
        p7,
        p8,
        p9,
        p10,
        p11,
        p12,
        nc,
        updateChangedFlags(changed) or 0b1,
        updateChangedFlags(changed1),
      )
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    p5: Any?,
    p6: Any?,
    p7: Any?,
    p8: Any?,
    p9: Any?,
    p10: Any?,
    p11: Any?,
    p12: Any?,
    p13: Any?,
    c: Composer,
    changed: Int,
    changed1: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed1 or if (c.changed(this)) differentBits(13) else sameBits(13)
    val result =
      (_block
        as
          (
        p1: Any?,
        p2: Any?,
        p3: Any?,
        p4: Any?,
        p5: Any?,
        p6: Any?,
        p7: Any?,
        p8: Any?,
        p9: Any?,
        p10: Any?,
        p11: Any?,
        p12: Any?,
        p13: Any?,
        c: Composer,
        changed: Int,
        changed1: Int,
      ) -> Any?)(
        p1,
        p2,
        p3,
        p4,
        p5,
        p6,
        p7,
        p8,
        p9,
        p10,
        p11,
        p12,
        p13,
        c,
        changed,
        dirty,
      )
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(
        p1,
        p2,
        p3,
        p4,
        p5,
        p6,
        p7,
        p8,
        p9,
        p10,
        p11,
        p12,
        p13,
        nc,
        updateChangedFlags(changed) or 0b1,
        updateChangedFlags(changed1),
      )
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    p5: Any?,
    p6: Any?,
    p7: Any?,
    p8: Any?,
    p9: Any?,
    p10: Any?,
    p11: Any?,
    p12: Any?,
    p13: Any?,
    p14: Any?,
    c: Composer,
    changed: Int,
    changed1: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed1 or if (c.changed(this)) differentBits(14) else sameBits(14)
    val result =
      (_block
        as
          (
        p1: Any?,
        p2: Any?,
        p3: Any?,
        p4: Any?,
        p5: Any?,
        p6: Any?,
        p7: Any?,
        p8: Any?,
        p9: Any?,
        p10: Any?,
        p11: Any?,
        p12: Any?,
        p13: Any?,
        p14: Any?,
        c: Composer,
        changed: Int,
        changed1: Int,
      ) -> Any?)(
        p1,
        p2,
        p3,
        p4,
        p5,
        p6,
        p7,
        p8,
        p9,
        p10,
        p11,
        p12,
        p13,
        p14,
        c,
        changed,
        dirty,
      )
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(
        p1,
        p2,
        p3,
        p4,
        p5,
        p6,
        p7,
        p8,
        p9,
        p10,
        p11,
        p12,
        p13,
        p14,
        nc,
        updateChangedFlags(changed) or 0b1,
        updateChangedFlags(changed1),
      )
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    p5: Any?,
    p6: Any?,
    p7: Any?,
    p8: Any?,
    p9: Any?,
    p10: Any?,
    p11: Any?,
    p12: Any?,
    p13: Any?,
    p14: Any?,
    p15: Any?,
    c: Composer,
    changed: Int,
    changed1: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed1 or if (c.changed(this)) differentBits(15) else sameBits(15)
    val result =
      (_block
        as
          (
        p1: Any?,
        p2: Any?,
        p3: Any?,
        p4: Any?,
        p5: Any?,
        p6: Any?,
        p7: Any?,
        p8: Any?,
        p9: Any?,
        p10: Any?,
        p11: Any?,
        p12: Any?,
        p13: Any?,
        p14: Any?,
        p15: Any?,
        c: Composer,
        changed: Int,
        changed1: Int,
      ) -> Any?)(
        p1,
        p2,
        p3,
        p4,
        p5,
        p6,
        p7,
        p8,
        p9,
        p10,
        p11,
        p12,
        p13,
        p14,
        p15,
        c,
        changed,
        dirty,
      )
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(
        p1,
        p2,
        p3,
        p4,
        p5,
        p6,
        p7,
        p8,
        p9,
        p10,
        p11,
        p12,
        p13,
        p14,
        p15,
        nc,
        updateChangedFlags(changed) or 0b1,
        updateChangedFlags(changed1),
      )
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    p5: Any?,
    p6: Any?,
    p7: Any?,
    p8: Any?,
    p9: Any?,
    p10: Any?,
    p11: Any?,
    p12: Any?,
    p13: Any?,
    p14: Any?,
    p15: Any?,
    p16: Any?,
    c: Composer,
    changed: Int,
    changed1: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed1 or if (c.changed(this)) differentBits(16) else sameBits(16)
    val result =
      (_block
        as
          (
        p1: Any?,
        p2: Any?,
        p3: Any?,
        p4: Any?,
        p5: Any?,
        p6: Any?,
        p7: Any?,
        p8: Any?,
        p9: Any?,
        p10: Any?,
        p11: Any?,
        p12: Any?,
        p13: Any?,
        p14: Any?,
        p15: Any?,
        p16: Any?,
        c: Composer,
        changed: Int,
        changed1: Int,
      ) -> Any?)(
        p1,
        p2,
        p3,
        p4,
        p5,
        p6,
        p7,
        p8,
        p9,
        p10,
        p11,
        p12,
        p13,
        p14,
        p15,
        p16,
        c,
        changed,
        dirty,
      )
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(
        p1,
        p2,
        p3,
        p4,
        p5,
        p6,
        p7,
        p8,
        p9,
        p10,
        p11,
        p12,
        p13,
        p14,
        p15,
        p16,
        nc,
        updateChangedFlags(changed) or 0b1,
        updateChangedFlags(changed1),
      )
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    p5: Any?,
    p6: Any?,
    p7: Any?,
    p8: Any?,
    p9: Any?,
    p10: Any?,
    p11: Any?,
    p12: Any?,
    p13: Any?,
    p14: Any?,
    p15: Any?,
    p16: Any?,
    p17: Any?,
    c: Composer,
    changed: Int,
    changed1: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed1 or if (c.changed(this)) differentBits(17) else sameBits(17)
    val result =
      (_block
        as
          (
        p1: Any?,
        p2: Any?,
        p3: Any?,
        p4: Any?,
        p5: Any?,
        p6: Any?,
        p7: Any?,
        p8: Any?,
        p9: Any?,
        p10: Any?,
        p11: Any?,
        p12: Any?,
        p13: Any?,
        p14: Any?,
        p15: Any?,
        p16: Any?,
        p17: Any?,
        c: Composer,
        changed: Int,
        changed1: Int,
      ) -> Any?)(
        p1,
        p2,
        p3,
        p4,
        p5,
        p6,
        p7,
        p8,
        p9,
        p10,
        p11,
        p12,
        p13,
        p14,
        p15,
        p16,
        p17,
        c,
        changed,
        dirty,
      )
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(
        p1,
        p2,
        p3,
        p4,
        p5,
        p6,
        p7,
        p8,
        p9,
        p10,
        p11,
        p12,
        p13,
        p14,
        p15,
        p16,
        p17,
        nc,
        updateChangedFlags(changed) or 0b1,
        updateChangedFlags(changed1),
      )
    }
    return result
  }

  override operator fun invoke(
    p1: Any?,
    p2: Any?,
    p3: Any?,
    p4: Any?,
    p5: Any?,
    p6: Any?,
    p7: Any?,
    p8: Any?,
    p9: Any?,
    p10: Any?,
    p11: Any?,
    p12: Any?,
    p13: Any?,
    p14: Any?,
    p15: Any?,
    p16: Any?,
    p17: Any?,
    p18: Any?,
    c: Composer,
    changed: Int,
    changed1: Int,
  ): Any? {
    val c = c.startRestartGroup(key)
    trackRead(c)
    val dirty = changed1 or if (c.changed(this)) differentBits(18) else sameBits(18)
    val result =
      (_block
        as
          (
        p1: Any?,
        p2: Any?,
        p3: Any?,
        p4: Any?,
        p5: Any?,
        p6: Any?,
        p7: Any?,
        p8: Any?,
        p9: Any?,
        p10: Any?,
        p11: Any?,
        p12: Any?,
        p13: Any?,
        p14: Any?,
        p15: Any?,
        p16: Any?,
        p17: Any?,
        p18: Any?,
        c: Composer,
        changed: Int,
        changed1: Int,
      ) -> Any?)(
        p1,
        p2,
        p3,
        p4,
        p5,
        p6,
        p7,
        p8,
        p9,
        p10,
        p11,
        p12,
        p13,
        p14,
        p15,
        p16,
        p17,
        p18,
        c,
        changed,
        dirty,
      )
    c.endRestartGroup()?.updateScope { nc, _ ->
      this(
        p1,
        p2,
        p3,
        p4,
        p5,
        p6,
        p7,
        p8,
        p9,
        p10,
        p11,
        p12,
        p13,
        p14,
        p15,
        p16,
        p17,
        p18,
        nc,
        updateChangedFlags(changed) or 0b1,
        updateChangedFlags(changed1),
      )
    }
    return result
  }
}

internal fun RecomposeScope?.replacableWith(other: RecomposeScope): Boolean =
  this == null ||
    (this is RecomposeScopeImpl &&
      other is RecomposeScopeImpl &&
      (!this.valid || this == other || this.anchor == other.anchor))

@ComposeCompilerApi
@Stable
interface ComposableLambda :
  Function2<Composer, Int, Any?>,
  Function3<Any?, Composer, Int, Any?>,
  Function4<Any?, Any?, Composer, Int, Any?>,
  Function5<Any?, Any?, Any?, Composer, Int, Any?>,
  Function6<Any?, Any?, Any?, Any?, Composer, Int, Any?>,
  Function7<Any?, Any?, Any?, Any?, Any?, Composer, Int, Any?>,
  Function8<Any?, Any?, Any?, Any?, Any?, Any?, Composer, Int, Any?>,
  Function9<Any?, Any?, Any?, Any?, Any?, Any?, Any?, Composer, Int, Any?>,
  Function10<Any?, Any?, Any?, Any?, Any?, Any?, Any?, Any?, Composer, Int, Any?>,
  Function11<Any?, Any?, Any?, Any?, Any?, Any?, Any?, Any?, Any?, Composer, Int, Any?>,
  Function13<
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Composer,
    Int,
    Int,
    Any?,
    >,
  Function14<
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Composer,
    Int,
    Int,
    Any?,
    >,
  Function15<
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Composer,
    Int,
    Int,
    Any?,
    >,
  Function16<
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Composer,
    Int,
    Int,
    Any?,
    >,
  Function17<
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Composer,
    Int,
    Int,
    Any?,
    >,
  Function18<
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Composer,
    Int,
    Int,
    Any?,
    >,
  Function19<
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Composer,
    Int,
    Int,
    Any?,
    >,
  Function20<
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Composer,
    Int,
    Int,
    Any?,
    >,
  Function21<
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Any?,
    Composer,
    Int,
    Int,
    Any?,
    >

private val lambdaKey = Any()

@Suppress("unused")
@ComposeCompilerApi fun composableLambda(
  composer: Composer,
  key: Int,
  tracked: Boolean,
  block: Any,
): ComposableLambda {
  // Use a rolled version of the key to avoid the key being a duplicate of the function's
  // key. This is particularly important for live edit scenarios where the groups will be
  // invalidated by the key number. This ensures that invalidating the function will not
  // also invalidate its lambda.
  //
  // 함수의 키와 중복되지 않도록 롤링된 버전의 키를 사용합니다. 이는 그룹이 키 번호로
  // 무효화되는 Live Edit 상황에서 특히 중요합니다. 이렇게 하면 함수가 무효화되더라도
  // 그 람다가 함께 무효화되지 않도록 보장합니다.
  composer.startMovableGroup(key = key rol 1, dataKey = lambdaKey)

  val slot = composer.rememberedValue()
  val result =
    if (slot === Composer.Empty) {
      val value = ComposableLambdaImpl(key = key, tracked = tracked, block = block)
      composer.updateRememberedValue(value = value)
      value
    } else {
      slot as ComposableLambdaImpl
      slot.update(block = block)
      slot
    }

  composer.endMovableGroup()
  return result
}

@Suppress("unused")
@ComposeCompilerApi fun composableLambdaInstance(key: Int, tracked: Boolean, block: Any): ComposableLambda =
  ComposableLambdaImpl(key = key, tracked = tracked, block = block)

@Suppress("unused")
@Composable
@ComposeCompilerApi fun rememberComposableLambda(key: Int, tracked: Boolean, block: Any): ComposableLambda =
  remember { ComposableLambdaImpl(key = key, tracked = tracked, block = block) }
    .also { it.update(block = block) }
