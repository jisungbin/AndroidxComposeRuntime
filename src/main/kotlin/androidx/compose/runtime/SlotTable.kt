/*
 * Copyright 2019 The Android Open Source Project
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

import androidx.collection.MutableIntList
import androidx.collection.MutableIntObjectMap
import androidx.collection.MutableIntSet
import androidx.collection.MutableObjectList
import androidx.collection.mutableIntListOf
import androidx.collection.mutableIntSetOf
import androidx.compose.runtime.collection.fastCopyInto
import androidx.compose.runtime.platform.makeSynchronizedObject
import androidx.compose.runtime.platform.synchronized
import androidx.compose.runtime.snapshots.fastAny
import androidx.compose.runtime.snapshots.fastFilterIndexed
import androidx.compose.runtime.snapshots.fastForEach
import androidx.compose.runtime.snapshots.fastMap
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// [Nomenclature]
//
// Address      - an absolute offset into the array ignoring its gap. See Index below.
//
// Anchor       - an encoding of Index that allows it to not need to be updated when groups or slots
//                are inserted or deleted. An anchor is positive if the Index it is tracking is
//                before the gap and negative if it is after the gap. If the Anchor is negative, it
//                records its distance from the end of the array. If slots or groups are inserted or
//                deleted this distance doesn't change but the index it represents automatically
//                reflects the deleted or inserted groups or slots. Anchors only need to be updated
//                if they track indexes whose group or slot Address moves when the gap moves.
//                The term anchor is used not only for the Anchor class but for the parent anchor
//                and data anchors in the group fields which are also anchors but not Anchor
//                instances.
//
// Aux          - auxiliary data that can be associated with a node and set independent of groups
//                slots. This is used, for example, by the composer to record CompositionLocal
//                maps as the map is not known at the when the group starts, only when the map is
//                calculated after using an arbitrary number of slots.
//
// Data         - the portion of the slot array associated with the group that contains the slots as
//                well as the ObjectKey, Node, and Aux if present. The slots for a group are after
//                the optional fixed group data.
//
// Group fields - a set of 5 contiguous integer elements in the groups array aligned at 5
//                containing the key, node count, group size, parent anchor and an anchor to the
//                group data and flags to indicate if the group is a node, has Aux or has an
//                ObjectKey. There are a set of extension methods used to access the group fields.
//
// Group        - a contiguous range in the groups array. The groups is an inlined array of group
//                fields. The group fields for a group and all of its children's fields comprise
//                a group. A groups describes how to interpret the slot array. Groups have an
//                integer key, and optional object key, node and aux and a 0 or more slots.
//                Groups form a tree where the child groups are immediately after the group
//                fields for the group. This data structure favors a linear scan of the children.
//                Random access is expensive unless it is through a group anchor.
//
// Index        - the logical index of a group or slot in its array. The index doesn't change when
//                the gap moves. See Address and Anchor above. The index and address of a group
//                or slot are identical if the gap is at the end of the buffer. This is taken
//                advantage of in the SlotReader.
//
// Key          - an Int value used as a key by the composer.
//
// Node         - a value of a node group that can be set independently of the slots of the group.
//                This is, for example, where the LayoutNode is stored by the slot table when
//                emitting using the UIEmitter.
//
// ObjectKey    - an object that can be used by the composer as part of a groups key. The key()
//                composable, for example, produces an ObjectKey. Using the key composable
//                function, for example, produces an ObjectKey value.
//
// Slot         - and element in the slot array. Slots are managed by and addressed through a group.
//                Slots are allocated in the slots array and are stored in order of their respective
//                groups.
//
//
//
// [용어 정리]
//
// Address      - gap을 무시한 배열의 절대 오프셋을 말합니다. 아래의 Index 항목을 참고하세요.
//
// Anchor       - 인덱스를 인코딩한 값으로, 그룹이나 슬롯이 삽입되거나 삭제될 때 갱신할 필요가 없도록 합니다.
//                추적 중인 인덱스가 gap 이전이면 양수이고 이후면 음수입니다. **앵커가 음수라면 배열 끝에서의
//                거리를 기록합니다.** 슬롯이나 그룹이 삽입되거나 삭제되어도 이 거리는 변하지 않으며, 앵커가
//                나타내는 인덱스는 삽입 또는 삭제된 그룹이나 슬롯을 자동으로 반영합니다. 앵커는 gap이 움직일 때
//                주소가 변하는 그룹이나 슬롯의 인덱스를 추적할 경우에만 갱신이 필요합니다. 앵커라는 용어는
//                Anchor 클래스뿐 아니라 그룹 필드의 부모 앵커와 데이터 앵커에도 사용합니다.
//
// Aux          - 그룹 슬롯과 독립적으로 노드에 연결하고 설정할 수 있는 보조 데이터입니다. 예를 들어 Composer가
//                CompositionLocal 맵을 기록할 때 사용합니다. 그룹이 시작될 때는 맵을 알 수 없고, 임의의 슬롯을
//                사용한 뒤 계산되므로 Aux에 저장합니다. (auxiliary: 보조의)
//
// Data         - 그룹에 연결된 슬롯 배열의 일부로, 슬롯뿐 아니라 ObjectKey, Node, Aux가 함께 포함될 수 있습니다.
//                그룹의 슬롯은 선택적 고정 그룹 데이터(optional fixed group data) 뒤에 위치합니다.
//                (portion: (더 큰 것의) 부분/일부)
//
// Group fields - groups 배열에서 5개 단위로 정렬된 연속된 5개의 정수 요소 집합으로, key, node 개수, group 크기,
//                parent anchor, group data의 anchor와 함께 그룹이 노드인지, Aux가 있는지, ObjectKey가 있는지를
//                나타내는 플래그를 포함합니다. 그룹 필드에 접근하기 위해 확장 메서드가 사용됩니다.
//
// Group        - groups 배열에서 연속된 구간을 말합니다. groups는 group field가 인라인된 배열입니다. 하나의 그룹은
//                해당 그룹의 group field와 그 자식 그룹들의 group field 전체로 구성됩니다. 그룹은 slot 배열을
//                해석하는 방법을 정의합니다. 그룹은 정수 key, 선택적 ObjectKey, Node, Aux, 0개 이상의 슬롯을
//                가질 수 있습니다. groups는 트리 구조를 이루며, 자식 그룹은 group의 group field 바로 뒤에 위치합니다.
//                이 자료 구조는 자식의 선형 탐색에 유리합니다. Random access는 그룹 앵커를 사용해야만 효율적입니다.
//                (comprise: …으로 구성되다[이뤄지다], interpret: (의미를) 설명[해석]하다)
//
// Index        - 배열 안의 그룹이나 슬롯의 논리적 인덱스를 말합니다. gap이 이동하더라도 인덱스는 변하지 않습니다.
//                위의 Address 및 Anchor 항목을 참고하세요. 그룹이나 슬롯의 인덱스와 주소는 gap이 버퍼 끝에 있을 때
//                동일합니다. SlotReader는 이 이점을 활용합니다.
//
// Key          - Composer가 그룹의 키로 사용하는 정수 값입니다.
//
// Node         - 그룹의 슬롯과 독립적으로 저장될 수 있는 노드 그룹의 값입니다. 예를 들어 LayoutNode는 UIEmitter가
//                내보낼 때 SlotTable에 기록됩니다.
//
// ObjectKey    - 그룹 키의 일부로 Composer가 사용하는 객체입니다. 예를 들어 key 컴포저블 함수가 생성하는 값을
//                말합니다.
//
// Slot         - 슬롯 배열의 원소를 말합니다. 슬롯은 그룹을 통해 관리되며 그룹 단위로 주소가 지정됩니다. 슬롯은
//                slots 배열에 할당되며, 각 그룹에 따라 순서대로 저장됩니다.

// All fields and variables referring to an array index are assumed to be Index values, not Address
// values, unless explicitly ending in Address.
//
// 배열 인덱스를 참조하는 모든 필드와 변수는 명시적으로 이름이 Address로 끝나지 않는 한 Address 값이
// 아니라 Index 값으로 간주합니다.

// For simplicity and efficiency of the reader, the gaps are always moved to the end resulting in
// Index and Address being identical in a reader.
//
// 단순성과 효율성을 위해 리더에서는 gap을 항상 끝으로 이동시키며, 그 결과 Index와 Address가 동일해집니다.

// The public API refers only to Index values. Address values are internal.
// 공개 API는 Index 값만을 참조합니다. Address 값은 내부용입니다.
//
// (과거엔 SlotTable 클래스가 public이었는데, 현재는 internal임. 아마 과거에만 유효했던 주석인 듯?)

internal class SlotTable : CompositionData, Iterable<CompositionGroup> {
  /**
   * An array to store group information that is stored as groups of [Group_Fields_Size] elements
   * of the array. The [groups] array can be thought of as an array of an inline struct.
   *
   * 그룹 정보를 저장하기 위한 배열로, 배열의 [Group_Fields_Size] 요소 단위로 그룹이 저장됩니다.
   * [groups] 배열은 인라인 구조체 배열로 볼 수 있습니다.
   */
  var groups = IntArray(0)
    private set

  /**
   * The number of groups contained in [groups].
   *  [groups]에 포함된 그룹의 개수입니다.
   */
  var groupsSize = 0
    private set

  /**
   * An array that stores the slots for a group. The slot elements for a group start at the offset
   * returned by [dataAnchor] of [groups] and continue to the next group's slots or to [slotsSize]
   * for the last group. When in a writer the [dataAnchor] is an anchor instead of an index as
   * [slots] might contain a gap.
   *
   * 그룹의 슬롯을 저장하는 배열입니다. 그룹의 슬롯 요소는 [groups]의 [dataAnchor]가 반환하는 오프셋에서
   * 시작하여 다음 그룹의 슬롯까지, 마지막 그룹의 경우 [slotsSize]까지 이어집니다. writer 상태에서는
   * [slots]에 gap이 있을 수 있으므로 [dataAnchor]는 인덱스가 아닌 앵커로 사용됩니다.
   */
  var slots = Array<Any?>(0) { null }
    private set

  /**
   * The number of slots used in [slots].
   * [slots]에서 사용된 슬롯의 개수입니다.
   */
  var slotsSize = 0
    private set

  /**
   * Tracks the number of active readers. A SlotTable can have multiple readers but only one
   * writer.
   *
   * 활성화된 리더의 수를 추적합니다. SlotTable은 여러 리더를 가질 수 있지만 작성자(writer)는
   * 하나만 가질 수 있습니다.
   */
  private var readers = 0

  private val lock = makeSynchronizedObject()

  /**
   * Tracks whether there is an active writer.
   * 활성화된 writer가 있는지 여부를 추적합니다.
   */
  internal var writer = false
    private set

  /**
   * An internal version that is incremented whenever a writer is created. This is used to detect
   * when an iterator created by [CompositionData] is invalid.
   *
   * writer가 생성될 때마다 증가하는 내부 버전입니다. 이는 [CompositionData]에 의해 생성된 iterator가
   * 무효화되었는지를 감지하는 데 사용됩니다.
   */
  internal var version = 0

  /**
   * A list of currently active anchors.
   * 현재 활성화된 앵커들의 목록입니다.
   */
  internal var anchors: ArrayList<Anchor> = arrayListOf()

  /**
   * A map of source information to anchor.
   * sourceInformation을 앵커에 매핑한 것입니다.
   */
  internal var sourceInformationMap: HashMap<Anchor, GroupSourceInformation>? = null

  /**
   * A map of source marker numbers to their, potentially indirect, parent key. This is recorded
   * for LiveEdit to allow a function that doesn't itself have a group to be invalidated.
   *
   * 소스 마커 번호를 그들의 (간접적일 수 있는) 부모 키에 매핑한 것입니다. 이는 LiveEdit에서
   * 그룹을 직접 가지지 않는 함수도 무효화될 수 있도록 기록됩니다.
   */
  internal var calledByMap: MutableIntObjectMap<MutableIntSet>? = null

  /**
   * Returns true if the slot table is empty.
   * 슬롯 테이블이 비어 있으면 true를 반환합니다.
   */
  override val isEmpty: Boolean
    get() = groupsSize == 0

  /**
   * Read the slot table in [block]. Any number of readers can be created but a slot table cannot
   * be read while it is being written to.
   *
   * 슬롯 테이블을 [block]에서 읽습니다. 리더는 여러 개 생성될 수 있지만, 슬롯 테이블이 기록 중일
   * 때는 읽을 수 없습니다.
   *
   * @see SlotReader
   */
  inline fun <T> read(block: (reader: SlotReader) -> T): T {
    val reader = openReader()
    try {
      return block(reader)
    } finally {
      reader.close()
    }
  }

  /**
   * Write to the slot table in [block]. Only one writer can be created for a slot table at a time
   * and all readers must be closed an do readers can be created while the slot table is being
   * written to.
   *
   * 슬롯 테이블을 [block]에서 기록합니다. 슬롯 테이블에는 한 번에 하나의 writer만 생성될 수 있으며,
   * 모든 리더가 닫혀야 하고 작성 중에는 새로운 리더를 생성할 수 없습니다.
   *
   * @see SlotWriter
   */
  inline fun <T> write(block: (writer: SlotWriter) -> T): T {
    val writer = openWriter()
    var normalClose = false
    try {
      return block(writer).also { normalClose = true }
    } finally {
      writer.close(normalClose)
    }
  }

  /**
   * Open a reader. Any number of readers can be created but a slot table cannot be read while it
   * is being written to.
   *
   * 리더를 엽니다. 리더는 여러 개 생성할 수 있지만, 슬롯 테이블이 기록 중일 때는 읽을 수 없습니다.
   *
   * @see SlotReader
   */
  fun openReader(): SlotReader {
    // writer가 대기 중일 때는 읽을 수 없습니다.
    if (writer) error("Cannot read while a writer is pending")

    readers++

    return SlotReader(table = this)
  }

  /**
   * Open a writer. Only one writer can be created for a slot table at a time and all readers must
   * be closed an do readers can be created while the slot table is being written to.
   *
   * writer를 엽니다. 슬롯 테이블에는 한 번에 하나의 writer만 생성할 수 있으며, 모든 리더가 닫혀야
   * 하고 작성 중에는 새로운 리더를 생성할 수 없습니다.
   *
   * @see SlotWriter
   */
  fun openWriter(): SlotWriter {
    runtimeCheck(!writer) {
      // 다른 writer가 대기 중일 때는 writer를 시작할 수 없습니다.
      "Cannot start a writer when another writer is pending"
    }

    runtimeCheck(readers <= 0) {
      // 리더가 대기 중일 때는 writer를 시작할 수 없습니다.
      "Cannot start a writer when a reader is pending"
    }

    writer = true
    version++

    return SlotWriter(table = this)
  }

  /**
   * Return an anchor to the given index. [anchorIndex] can be used to determine the current index
   * of the group currently at [index] after a [SlotWriter] as inserted or removed groups or the
   * group itself was moved. [Anchor.valid] will be `false` if the group at [index] was removed.
   *
   * If an anchor is moved using [SlotWriter.moveFrom] or [SlotWriter.moveTo] the anchor will move
   * to be owned by the receiving table. [ownsAnchor] can be used to determine if the group at
   * [index] is still in this table.
   *
   * 주어진 인덱스에 대한 앵커를 반환합니다. [SlotWriter]가 그룹을 삽입하거나 제거했거나 그룹 자체가
   * 이동된 이후에는 [anchorIndex]를 사용하여 현재 [index] 위치의 그룹 인덱스를 확인할 수 있습니다.
   * [index]의 그룹이 제거되었다면 [Anchor.valid]는 false를 반환합니다.
   *
   * 앵커가 [SlotWriter.moveFrom]이나 [SlotWriter.moveTo]로 이동되면 앵커는 수신 테이블에 속하게
   * 됩니다. [ownsAnchor]를 사용하면 [index]의 그룹이 여전히 이 테이블에 속하는지 확인할 수 있습니다.
   */
  fun anchor(index: Int): Anchor {
    runtimeCheck(!writer) {
      // 대신 활성화된 SlotWriter를 사용하여 앵커 위치를 생성합니다.
      // (기존에 활성화된 writer가 있는 경우에 해당)
      "use active SlotWriter to create an anchor location instead"
    }

    requirePrecondition(index in 0 until groupsSize) {
      // 매개변수 인덱스가 범위를 벗어났습니다.
      "Parameter index is out of range"
    }

    return anchors.getOrAdd(index = index, effectiveSize = groupsSize) {
      Anchor(location = index)
    }
  }

  /**
   * Return an anchor to the given index if there is one already, null otherwise.
   * 이미 존재한다면 주어진 인덱스에 대한 앵커를 반환하고, 그렇지 않으면 null을 반환합니다.
   */
  private fun tryAnchor(index: Int): Anchor? {
    runtimeCheck(!writer) {
      // 대신 활성화된 SlotWriter를 사용하여 해당 위치에 대한 앵커를 생성하세요.
      "use active SlotWriter to crate an anchor for location instead"
    }

    return if (index in 0 until groupsSize)
      anchors.find(index = index, effectiveSize = groupsSize)
    else
      null
  }

  /**
   * Return the group index for [anchor]. This [SlotTable] is assumed to own [anchor] but that is
   * not validated. If [anchor] is not owned by this [SlotTable] the result is undefined. If a
   * [SlotWriter] is open the [SlotWriter.anchorIndex] must be called instead as [anchor] might be
   * affected by the modifications being performed by the [SlotWriter].
   *
   * [anchor]의 그룹 인덱스를 반환합니다. 이 [SlotTable]이 [anchor]를 소유한다고 가정하며, 이는
   * 검증되지 않습니다. [anchor]가 이 [SlotTable]의 소유가 아니라면 결과는 undefined 입니다.
   * [SlotWriter]가 열려 있다면 [SlotWriter]에 의해 수행 중인 수정의 영향을 받을 수 있으므로
   * [SlotWriter.anchorIndex]를 대신 호출해야 합니다.
   */
  fun anchorIndex(anchor: Anchor): Int {
    runtimeCheck(!writer) {
      // 대신 활성화된 SlotWriter를 사용하여 앵커 위치를 결정하세요.
      "Use active SlotWriter to determine anchor location instead"
    }

    requirePrecondition(anchor.valid) {
      // 앵커가 제거된 그룹을 참조하고 있습니다.
      "Anchor refers to a group that was removed"
    }

    return anchor.location
  }

  /**
   * Returns true if [anchor] is owned by this [SlotTable] or false if it is owned by a different
   * [SlotTable] or no longer part of this table (e.g. it was moved or the group it was an anchor
   * for was removed).
   *
   * [anchor]가 이 [SlotTable]에 속해 있다면 true를 반환하고, 다른 [SlotTable]에 속해 있거나 더
   * 이상 이 테이블의 일부가 아니라면(예: 이동되었거나, 앵커가 가리키던 그룹이 제거된 경우) false를
   * 반환합니다.
   */
  fun ownsAnchor(anchor: Anchor): Boolean =
    anchor.valid &&
      anchors.searchAnchorLocation(location = anchor.location, effectiveSize = groupsSize).let { loc ->
        loc >= 0 && anchors[loc] == anchor
      }

  fun inGroup(groupAnchor: Anchor, anchor: Anchor): Boolean {
    val group = groupAnchor.location
    val groupEnd = group + groups.groupSize(address = group)

    return anchor.location in group until groupEnd
  }

  /**
   * Returns true if the [anchor] is for the group at [groupIndex] or one of it child groups.
   * [anchor]가 [groupIndex]의 그룹이나 그 자식 그룹 중 하나를 가리키면 true를 반환합니다.
   */
  fun groupContainsAnchor(groupIndex: Int, anchor: Anchor): Boolean {
    runtimeCheck(!writer) { "Writer is active" }
    runtimeCheck(groupIndex in 0 until groupsSize) { "Invalid group index" }

    return ownsAnchor(anchor = anchor) &&
      anchor.location in groupIndex until (groupIndex + groups.groupSize(address = groupIndex))
  }

  /** Close [reader]. */
  internal fun close(
    reader: SlotReader,
    sourceInformationMap: HashMap<Anchor, GroupSourceInformation>?,
  ) {
    runtimeCheck(reader.table === this && readers > 0) { "Unexpected reader close()" }

    readers--

    if (sourceInformationMap != null) {
      synchronized(lock) {
        val thisMap = this.sourceInformationMap
        if (thisMap != null) {
          thisMap.putAll(sourceInformationMap)
        } else {
          this.sourceInformationMap = sourceInformationMap
        }
      }
    }
  }

  /**
   * Close [writer] and adopt the slot arrays returned. The [SlotTable] is invalid until
   * [SlotWriter.close] is called as the [SlotWriter] is modifying [groups] and [slots]
   * directly and will only make copies of the arrays if the slot table grows.
   *
   * [writer]를 닫고 반환된 슬롯 배열을 적용합니다. [SlotWriter]가 [groups]와 [slots]를
   * 직접 수정하므로 [SlotWriter.close]가 호출되기 전까지 [SlotTable]은 유효하지 않습니다.
   * 슬롯 테이블이 커지는 경우에만 배열이 복사됩니다.
   */
  internal fun close(
    writer: SlotWriter,
    groups: IntArray,
    groupsSize: Int,
    slots: Array<Any?>,
    slotsSize: Int,
    anchors: ArrayList<Anchor>,
    sourceInformationMap: HashMap<Anchor, GroupSourceInformation>?,
    calledByMap: MutableIntObjectMap<MutableIntSet>?,
  ) {
    requirePrecondition(writer.table === this && this.writer) { "Unexpected writer close()" }

    this.writer = false

    setTo(
      groups = groups,
      groupsSize = groupsSize,
      slots = slots,
      slotsSize = slotsSize,
      anchors = anchors,
      sourceInformationMap = sourceInformationMap,
      calledByMap = calledByMap,
    )
  }

  /**
   * Used internally by [SlotWriter.moveFrom] to swap arrays with a slot table target [SlotTable]
   * is empty.
   *
   * [SlotWriter.moveFrom]에서 내부적으로 사용되며, 대상 [SlotTable]이 비어 있을 때 배열을
   * 교환합니다.
   */
  internal fun setTo(
    groups: IntArray,
    groupsSize: Int,
    slots: Array<Any?>,
    slotsSize: Int,
    anchors: ArrayList<Anchor>,
    sourceInformationMap: HashMap<Anchor, GroupSourceInformation>?,
    calledByMap: MutableIntObjectMap<MutableIntSet>?,
  ) {
    // Adopt the slots from the writer.
    // writer로부터 슬롯을 받아들입니다.

    this.groups = groups
    this.groupsSize = groupsSize
    this.slots = slots
    this.slotsSize = slotsSize
    this.anchors = anchors
    this.sourceInformationMap = sourceInformationMap
    this.calledByMap = calledByMap
  }

  /**
   * Modifies the current slot table such that every group with the target key will be
   * invalidated, and when recomposed, the content of those groups will be disposed and
   * re-inserted.
   *
   * This is currently only used for developer tooling such as Live Edit to invalidate groups
   * which we know will no longer have the same structure so we want to remove them before
   * recomposing.
   *
   * Returns a list of groups if they were successfully invalidated. If this returns null then a
   * full composition must be forced.
   *
   *
   * 현재 슬롯 테이블을 수정하여, 대상 키를 가진 모든 그룹이 무효화되도록 합니다. 재구성 시 해당
   * 그룹들의 콘텐츠는 dispose되고 다시 삽입됩니다.
   *
   * 이는 현재 Live Edit과 같은 개발자 도구에서만 사용되며, 동일한 구조를 더 이상 유지하지 않을
   * 것으로 예상되는 그룹을 재구성 전에 제거하기 위해 사용됩니다.
   *
   * 성공적으로 무효화되면 그룹들의 목록을 반환합니다. null을 반환하면 전체 composition을 강제로
   * 수행해야 합니다.
   */
  internal fun invalidateGroupsWithKey(target: Int): List<RecomposeScopeImpl>? {
    val anchors = mutableListOf<Anchor>()
    val scopes = mutableListOf<RecomposeScopeImpl>()
    var allScopesFound = true
    val set =
      MutableIntSet().also {
        it.add(target)
        it.add(LIVE_EDIT_INVALID_KEY)
      }

    calledByMap?.get(target)?.let(set::addAll)

    // Invalidate groups with the target key.
    // 대상 키를 가진 그룹들을 무효화합니다.
    read { reader ->
      fun scanGroup() {
        val key = reader.groupKey
        if (key in set) {
          if (key != LIVE_EDIT_INVALID_KEY) anchors.add(reader.anchor())

          if (allScopesFound) {
            val nearestScope = findEffectiveRecomposeScope(group = reader.currentGroup)
            if (nearestScope != null) {
              scopes.add(nearestScope)
              if (nearestScope.anchor?.location == reader.currentGroup) {
                // For the group that contains the restart group then, in some
                // cases, such as when the parameter names of a function change,
                // the restart lambda can be invalid if it is called. To avoid this
                // the scope parent scope needs to be invalidated too.
                //
                // 재시작 그룹을 포함하는 그룹의 경우, 함수의 매개변수 이름이 바뀌는
                // 등의 상황에서는 재시작 람다가 호출될 때 무효할 수 있습니다. 이를
                // 피하기 위해 부모 스코프도 함께 무효화해야 합니다.
                val parentScope = findEffectiveRecomposeScope(group = reader.parent)
                parentScope?.let(scopes::add)
              }
            } else {
              allScopesFound = false
              scopes.clear()
            }
          }

          reader.skipGroup()
          return
        }

        reader.startGroup()
        while (!reader.isGroupEnd) {
          scanGroup()
        }
        reader.endGroup()
      }

      scanGroup()
    }

    // Bash groups even if we could not invalidate it. The call is responsible for ensuring
    // the group is recomposed when this happens.
    //
    // 무효화할 수 없는 경우에도 그룹을 강제로 무효화합니다. 이때 그룹이 다시 재구성되도록
    // 보장하는 책임은 호출 측에 있습니다.
    write { writer ->
      writer.startGroup()
      anchors.fastForEach { anchor ->
        if (anchor.toIndexFor(writer = writer) >= writer.currentGroup) {
          writer.seek(anchor = anchor)
          writer.bashCurrentGroup()
        }
      }
      writer.skipToGroupEnd()
      writer.endGroup()
    }

    return if (allScopesFound) scopes else null
  }

  /**
   * Turns true if the first group (considered the root group) contains a mark.
   * 첫 번째 그룹(루트 그룹으로 간주됨)이 mark를 포함하면 true를 반환합니다.
   */
  fun containsMark(): Boolean = groupsSize > 0 && groups.containsMark(address = 0)

  fun sourceInformationOf(group: Int): GroupSourceInformation? =
    sourceInformationMap?.let { map -> tryAnchor(index = group)?.let(map::get) }

  /**
   * Find the nearest recompose scope for [group] that, when invalidated, will cause [group] group
   * to be recomposed. This will force non-restartable recompose scopes in between this [group]
   * and the restartable group to recompose.
   *
   * [group]에 대해 가장 가까운 recompose scope를 찾아, 그것이 무효화될 때 [group]이 다시
   * 재구성되도록 합니다. 이 과정에서 [group]과 restartable 그룹 사이에 있는
   * non-restartable recompose scope들도 강제로 재구성됩니다.
   */
  private fun findEffectiveRecomposeScope(group: Int): RecomposeScopeImpl? {
    var current = group
    while (current > 0) {
      for (data in DataIterator(table = this, group = current)) {
        if (data is RecomposeScopeImpl) {
          if (data.used && current != group)
            return data
          else
            data.forcedRecompose = true
        }
      }

      current = groups.parentAnchor(address = current)
    }

    return null
  }

  /**
   * A debugging aid to validate the internal structure of the slot table. Throws an exception if
   * the slot table is not in the expected shape.
   *
   * 슬롯 테이블의 내부 구조를 검증하기 위한 디버깅 도구입니다. 슬롯 테이블이 예상된 형태가 아니면
   * 예외를 발생시킵니다.
   */
  fun verifyWellFormed() {
    // If the check passes Address and Index are identical so there is no need for
    // indexToAddress conversions.
    //
    // 검사가 통과되면 Address와 Index가 동일하므로 indexToAddress 변환이 필요하지 않습니다.
    var current = 0

    fun validateGroup(parent: Int, parentEnd: Int): Int {
      val group = current++
      val parentIndex = groups.parentAnchor(address = group)

      checkPrecondition(parentIndex == parent) {
        // $group에서 잘못된 부모 인덱스가 감지되었습니다. 예상된 부모 인덱스는 $parent인데,
        // 실제로는 $parentIndex가 발견되었습니다.
        "Invalid parent index detected at $group, expected parent index to be $parent " +
          "found $parentIndex"
      }

      val end = group + groups.groupSize(address = group)

      checkPrecondition(end <= groupsSize) {
        // $group에서 그룹이 테이블 끝을 넘어섰습니다.
        "A group extends past the end of the table at $group"
      }
      checkPrecondition(end <= parentEnd) {
        // $group에서 그룹이 부모 그룹을 넘어섰습니다.
        "A group extends past its parent group at $group"
      }

      val dataStart = groups.dataAnchor(address = group)
      val dataEnd =
        if (group >= groupsSize - 1)
          slotsSize
        else
          groups.dataAnchor(address = group + 1)

      checkPrecondition(dataEnd <= slots.size) {
        // $group의 슬롯이 슬롯 테이블 끝을 넘어섰습니다.
        "Slots for $group extend past the end of the slot table"
      }
      checkPrecondition(dataStart <= dataEnd) {
        // $group에서 잘못된 데이터 앵커가 감지되었습니다.
        "Invalid data anchor at $group"
      }

      val slotStart = groups.slotAnchor(address = group)

      checkPrecondition(slotStart <= dataEnd) {
        // $group에서 슬롯 시작이 범위를 벗어났습니다.
        "Slots start out of range at $group"
      }

      val minSlotsNeeded: Int =
        (if (groups.isNode(address = group)) 1 else 0) +
          (if (groups.hasObjectKey(address = group)) 1 else 0) +
          (if (groups.hasAux(address = group)) 1 else 0)

      checkPrecondition(dataEnd - dataStart >= minSlotsNeeded) {
        // 그룹 $group에 대해 추가된 슬롯이 충분하지 않습니다.
        "Not enough slots added for group $group"
      }

      val isNode = groups.isNode(address = group)

      checkPrecondition(value = !isNode || slots[groups.nodeIndex(address = group)] != null) {
        // $group에서 노드 그룹에 대한 노드가 기록되지 않았습니다.
        "No node recorded for a node group at $group"
      }

      var nodeCount = 0
      while (current < end) {
        nodeCount += validateGroup(parent = group, parentEnd = end)
      }

      val expectedNodeCount = groups.nodeCount(address = group)
      val expectedSlotCount = groups.groupSize(address = group)

      checkPrecondition(expectedNodeCount == nodeCount) {
        //
        "Incorrect node count detected at $group, " +
          "expected $expectedNodeCount, received $nodeCount"
      }

      val actualSlotCount = current - group

      checkPrecondition(expectedSlotCount == actualSlotCount) {
        // $group에서 잘못된 노드 개수가 감지되었습니다. 예상된 노드 개수는 $expectedNodeCount인데,
        // 실제로는 $nodeCount가 확인되었습니다.
        "Incorrect slot count detected at $group, expected $expectedSlotCount, received " +
          "$actualSlotCount"
      }

      if (groups.containsAnyMark(address = group)) {
        checkPrecondition(group <= 0 || groups.containsMark(address = parent)) {
          // $group이 mark를 포함하므로, 그룹 $parent가 이를 포함한다고 기록되어야 합니다.
          "Expected group $parent to record it contains a mark because $group does"
        }
      }

      return if (isNode) 1 else nodeCount
    }

    if (groupsSize > 0) {
      while (current < groupsSize) {
        validateGroup(
          parent = -1,
          parentEnd = current + groups.groupSize(address = current),
        )
      }

      checkPrecondition(current == groupsSize) {
        // 루트 $current에서 불완전한 그룹이 발견되었습니다. 예상된 크기는 $groupsSize입니다.
        "Incomplete group at root $current expected to be $groupsSize"
      }
    }

    // Verify that slot gap contains all nulls.
    // 슬롯 갭이 모두 null로 채워져 있는지 확인합니다.
    for (index in slotsSize until slots.size) {
      checkPrecondition(slots[index] == null) {
        // 슬롯 갭의 인덱스 $index에서 null이 아닌 값이 발견되었습니다.
        "Non null value in the slot gap at index $index"
      }
    }

    // Verify anchors are well-formed.
    // 앵커들이 올바르게 형성되어 있는지 확인합니다.
    var lastLocation = -1

    anchors.fastForEach { anchor ->
      val location = anchor.toIndexFor(slots = this)

      requirePrecondition(location in 0..groupsSize) {
        // 잘못된 앵커입니다. 위치가 범위를 벗어났습니다.
        "Invalid anchor, location out of bound"
      }
      requirePrecondition(lastLocation < location) {
        // 앵커의 순서가 올바르지 않습니다.
        "Anchor is out of order"
      }

      lastLocation = location
    }

    // Verify source information is well-formed.
    // 소스 정보가 올바르게 형성되어 있는지 확인합니다.
    fun verifySourceGroup(group: GroupSourceInformation) {
      group.groups?.fastForEach { groupItem ->
        when (groupItem) {
          is Anchor -> {
            requirePrecondition(groupItem.valid) {
              // 소스 맵에 잘못된 앵커가 포함되어 있습니다.
              "Source map contains invalid anchor"
            }
            requirePrecondition(ownsAnchor(anchor = groupItem)) {
              // 소스 맵 앵커가 슬롯 테이블에 속하지 않습니다.
              "Source map anchor is not owned by the slot table"
            }
          }
          is GroupSourceInformation -> verifySourceGroup(group = groupItem)
        }
      }
    }

    sourceInformationMap?.let { sourceInformationMap ->
      for ((anchor, sourceGroup) in sourceInformationMap) {
        requirePrecondition(anchor.valid) {
          // 소스 맵에 잘못된 앵커가 포함되어 있습니다.
          "Source map contains invalid anchor"
        }
        requirePrecondition(ownsAnchor(anchor = anchor)) {
          // 소스 맵 앵커가 슬롯 테이블에 속하지 않습니다.
          "Source map anchor is not owned by the slot table"
        }
        verifySourceGroup(group = sourceGroup)
      }
    }
  }

  fun collectCalledByInformation() {
    calledByMap = MutableIntObjectMap()
  }

  fun collectSourceInformation() {
    sourceInformationMap = HashMap()
  }

  /**
   * A debugging aid that renders the slot table as a string. [toString] is avoided as producing
   * this information is potentially a very expensive operation for large slot tables and calling
   * this function in the debugger should never be implicit which it often is for [toString].
   *
   * 슬롯 테이블을 문자열로 렌더링하는 디버깅 도구입니다. 큰 슬롯 테이블에서는 이 정보를 생성하는 데
   * 매우 많은 비용이 들 수 있으므로 [toString]은 피합니다. 또한 디버거에서 이 함수를 호출하는 것은
   * [toString]처럼 암묵적으로 발생해서는 안 됩니다.
   */
  @Suppress("unused", "MemberVisibilityCanBePrivate")
  fun toDebugString(): String =
    if (writer) super.toString()
    else buildString {
      append(super.toString())
      append('\n')

      val groupsSize = groupsSize
      if (groupsSize > 0) {
        var current = 0
        while (current < groupsSize) {
          current += emitGroup(index = current, level = 0)
        }
      } else append("<EMPTY>")
    }

  /**
   * A helper function used by [toDebugString] to render a particular group.
   * [toDebugString]에서 특정 그룹을 렌더링하는 데 사용되는 보조 함수입니다.
   */
  private fun StringBuilder.emitGroup(index: Int, level: Int): Int {
    repeat(level) { append(' ') }
    append("Group(")
    append(index)
    append(")")

    sourceInformationOf(group = index)?.sourceInformation?.let { sourceInfo ->
      if (sourceInfo.startsWith("C(") || sourceInfo.startsWith("CC(")) {
        val start = sourceInfo.indexOf("(") + 1
        val endParen = sourceInfo.indexOf(')')
        append(" ")
        append(sourceInfo.substring(start, endParen))
        append("()")
      }
    }

    append(" key=")
    append(groups.key(address = index))

    fun dataIndex(address: Int): Int =
      if (address >= groupsSize) slotsSize else groups.dataAnchor(address = address)

    val groupSize = groups.groupSize(address = index)

    append(", nodes=")
    append(groups.nodeCount(address = index))
    append(", size=")
    append(groupSize)

    if (groups.hasMark(address = index)) {
      append(", mark")
    }
    if (groups.containsMark(address = index)) {
      append(", contains mark")
    }

    val dataStart = dataIndex(address = index)
    val dataEnd = dataIndex(address = index + 1)
    if (dataStart in 0..dataEnd && dataEnd <= slotsSize) {
      if (groups.hasObjectKey(address = index)) {
        append(
          " objectKey=${
            slots[groups.objectKeyIndex(address = index)].toString().summarize(minSize = 10)
          }"
        )
      }

      if (groups.isNode(index)) {
        append(" node=${slots[groups.nodeIndex(address = index)].toString().summarize(minSize = 10)}")
      }

      if (groups.hasAux(index)) {
        append(" aux=${slots[groups.auxIndex(address = index)].toString().summarize(minSize = 10)}")
      }

      val slotStart = groups.slotAnchor(address = index)
      if (slotStart < dataEnd) {
        append(", slots=[")
        append(slotStart)
        append(": ")
        for (dataIndex in slotStart until dataEnd) {
          if (dataIndex != slotStart) append(", ")
          append(slots[dataIndex].toString().summarize(10))
        }
        append("]")
      }
    } else {
      append(", *invalid data offsets $dataStart-$dataEnd*")
    }

    append('\n')

    var current = index + 1
    val end = index + groupSize
    while (current < end) {
      current += emitGroup(index = current, level = level + 1)
    }

    return groupSize
  }

  /** A debugging aid to list all the keys [key] values in the [groups] array. */
  @Suppress("unused") private fun keys() = groups.keys(groupsSize * Group_Fields_Size)

  /** A debugging aid to list all the [nodeCount] values in the [groups] array. */
  @Suppress("unused") private fun nodes() = groups.nodeCounts(groupsSize * Group_Fields_Size)

  /** A debugging aid to list all the [parentAnchor] values in the [groups] array. */
  @Suppress("unused")
  private fun parentIndexes() = groups.parentAnchors(groupsSize * Group_Fields_Size)

  /** A debugging aid to list all the indexes into the [slots] array from the [groups] array. */
  @Suppress("unused")
  private fun dataIndexes() = groups.dataAnchors(groupsSize * Group_Fields_Size)

  /** A debugging aid to list the [groupsSize] of all the groups in [groups]. */
  @Suppress("unused") private fun groupSizes() = groups.groupSizes(groupsSize * Group_Fields_Size)

  @Suppress("unused")
  internal fun slotsOf(group: Int): List<Any?> {
    val start = groups.dataAnchor(group)
    val end = if (group + 1 < groupsSize) groups.dataAnchor(group + 1) else slots.size
    return slots.toList().subList(start, end)
  }

  internal fun slot(group: Int, slotIndex: Int): Any? {
    val start = groups.slotAnchor(group)
    val end = if (group + 1 < groupsSize) groups.dataAnchor(group + 1) else slots.size
    val len = end - start
    return if (slotIndex in 0 until len) return slots[start + slotIndex] else Composer.Empty
  }

  override val compositionGroups: Iterable<CompositionGroup>
    get() = this

  override fun iterator(): Iterator<CompositionGroup> = GroupIterator(this, 0, groupsSize)

  override fun find(identityToFind: Any): CompositionGroup? =
    SlotTableGroup(this, 0).find(identityToFind)
}

/**
 * An [Anchor] tracks a groups as its index changes due to other groups being inserted and removed
 * before it. If the group the [Anchor] is tracking is removed, directly or indirectly, [valid] will
 * return false. The current index of the group can be determined by passing either the [SlotTable]
 * or [SlotWriter] to [toIndexFor]. If a [SlotWriter] is active, it must be used instead of the
 * [SlotTable] as the anchor index could have shifted due to operations performed on the writer.
 *
 * [Anchor]는 자신이 추적하는 그룹 앞에 다른 그룹들이 삽입되거나 제거되면서 인덱스가 바뀌더라도
 * 해당 그룹을 추적합니다. [Anchor]가 추적하는 그룹이 직접 또는 간접적으로 제거되면 [valid]는
 * false를 반환합니다. 그룹의 현재 인덱스는 [SlotTable]이나 [SlotWriter]를 [toIndexFor]에 전달해
 * 확인할 수 있습니다. [SlotWriter]가 활성 상태라면 반드시 [SlotTable] 대신 사용해야 하는데,
 * 이는 writer에서 수행된 작업 때문에 앵커 인덱스가 달라졌을 수 있기 때문입니다.
 */
internal class Anchor(internal var location: Int) {
  val valid: Boolean
    get() = location != Int.MIN_VALUE

  fun toIndexFor(slots: SlotTable): Int = slots.anchorIndex(anchor = this)

  fun toIndexFor(writer: SlotWriter): Int = writer.anchorIndex(anchor = this)

  override fun toString(): String = "${super.toString()}{ location = $location }"
}

internal class GroupSourceInformation(
  val key: Int,
  var sourceInformation: String?,
  val dataStartOffset: Int,
) {
  var groups: ArrayList<Any /* Anchor | GroupSourceInformation */>? = null
  var closed = false
  var dataEndOffset: Int = 0

  // Return the current open nested source information or this.
  // 현재 열려 있는 중첩 sourceInformation을 반환하거나, 없으면 this를 반환합니다.
  private fun openInformation(): GroupSourceInformation =
    (groups?.let { groups ->
      groups.fastLastOrNull { it is GroupSourceInformation && !it.closed }
    }
      as? GroupSourceInformation)?.openInformation() ?: this

  fun startGrouplessCall(
    key: Int,
    sourceInformation: String,
    dataOffset: Int,
  ) {
    openInformation().add(
      group = GroupSourceInformation(
        key = key,
        sourceInformation = sourceInformation,
        dataStartOffset = dataOffset,
      ),
    )
  }

  fun endGrouplessCall(dataOffset: Int) {
    openInformation().close(dataOffset = dataOffset)
  }

  fun reportGroup(writer: SlotWriter, group: Int) {
    openInformation().add(group = writer.anchor(index = group))
  }

  fun reportGroup(table: SlotTable, group: Int) {
    openInformation().add(group = table.anchor(index = group))
  }

  fun addGroupAfter(writer: SlotWriter, predecessor: Int, group: Int) {
    val groups = groups ?: ArrayList<Any>().also { groups = it }
    val index =
      if (predecessor >= 0) {
        val anchor = writer.tryAnchor(group = predecessor)
        if (anchor != null) {
          groups.fastIndexOf { group ->
            group == anchor ||
              (group is GroupSourceInformation && group.hasAnchor(anchor = anchor))
          }
        } else 0
      } else 0

    groups.add(
      /* index = */ index,
      /* element = */ writer.anchor(index = group),
    )
  }

  fun close(dataOffset: Int) {
    closed = true
    dataEndOffset = dataOffset
  }

  private fun add(group: Any /* Anchor | GroupSourceInformation */) {
    val groups = groups ?: ArrayList<Any>().also { this.groups = it }
    groups.add(group)
  }

  private fun hasAnchor(anchor: Anchor): Boolean =
    groups?.fastAny { group ->
      group == anchor ||
        (group is GroupSourceInformation && group.hasAnchor(anchor = anchor))
    } == true

  fun removeAnchor(anchor: Anchor): Boolean {
    val groups = groups

    if (groups != null) {
      var index = groups.lastIndex

      while (index >= 0) {
        when (val item = groups[index]) {
          is Anchor -> if (item == anchor) groups.removeAt(index)
          is GroupSourceInformation ->
            if (!item.removeAnchor(anchor = anchor)) {
              groups.removeAt(index)
            }
        }

        index--
      }

      if (groups.isEmpty()) {
        this.groups = null
        return false
      }

      return true
    }

    return true
  }
}

private inline fun <T> ArrayList<T>.fastLastOrNull(predicate: (T) -> Boolean): T? {
  var index = size - 1
  while (index >= 0) {
    val value = get(index)
    if (predicate(value)) return value
    index--
  }
  return null
}

private inline fun <T> ArrayList<T>.fastIndexOf(predicate: (T) -> Boolean): Int {
  var index = 0
  val size = size
  while (index < size) {
    val value = get(index)
    if (predicate(value)) return index
    index++
  }
  return -1
}

/** A reader of a slot table. See [SlotTable] */
internal class SlotReader(
  /** The table for whom this is a reader. */
  internal val table: SlotTable,
) {

  /** A copy of the [SlotTable.groups] array to avoid having indirect through [table]. */
  private val groups: IntArray = table.groups

  /** A copy of [SlotTable.groupsSize] to avoid having to indirect through [table]. */
  private val groupsSize: Int = table.groupsSize

  /** A copy of [SlotTable.slots] to avoid having to indirect through [table]. */
  private var slots: Array<Any?> = table.slots

  /** A Copy of [SlotTable.slotsSize] to avoid having to indirect through [table]. */
  private val slotsSize: Int = table.slotsSize

  /**
   * A local copy of the [sourceInformationMap] being created to be merged into [table] when the
   * reader closes.
   */
  private var sourceInformationMap: HashMap<Anchor, GroupSourceInformation>? = null

  /** True if the reader has been closed */
  var closed: Boolean = false
    private set

  /** The current group that will be started with [startGroup] or skipped with [skipGroup]. */
  var currentGroup = 0

  /** The end of the [parent] group. */
  var currentEnd = groupsSize
    private set

  /** The parent of the [currentGroup] group which is the last group started with [startGroup]. */
  var parent = -1
    private set

  /** The current location of the current slot to restore [endGroup] is called. */
  private val currentSlotStack = IntStack()

  /** The number of times [beginEmpty] has been called. */
  private var emptyCount = 0

  /**
   * The current slot of [parent]. This slot will be the next slot returned by [next] unless it is
   * equal ot [currentSlotEnd].
   */
  private var currentSlot = 0

  /** The current end slot of [parent]. */
  private var currentSlotEnd = 0

  /** Return the total number of groups in the slot table. */
  val size: Int
    get() = groupsSize

  /** Return the current slot of the group whose value will be returned by calling [next]. */
  val slot: Int
    get() = currentSlot - groups.slotAnchor(parent)

  /** Return the parent index of [index]. */
  fun parent(index: Int) = groups.parentAnchor(index)

  /** Determine if the slot is start of a node. */
  val isNode: Boolean
    get() = groups.isNode(currentGroup)

  /** Determine if the group at [index] is a node. */
  fun isNode(index: Int) = groups.isNode(index)

  /**
   * The number of nodes managed by the current group. For node groups, this is the list of the
   * children nodes.
   */
  val nodeCount: Int
    get() = groups.nodeCount(currentGroup)

  /** Return the number of nodes for the group at [index]. */
  fun nodeCount(index: Int) = groups.nodeCount(index)

  /** Return the node at [index] if [index] is a node group else null. */
  fun node(index: Int): Any? = if (groups.isNode(index)) groups.node(index) else null

  /** Determine if the reader is at the end of a group and an [endGroup] is expected. */
  val isGroupEnd
    get() = inEmpty || currentGroup == currentEnd

  /** Determine if a [beginEmpty] has been called. */
  val inEmpty
    get() = emptyCount > 0

  /** Get the size of the group at [currentGroup]. */
  val groupSize
    get() = groups.groupSize(currentGroup)

  /**
   * Get the size of the group at [index]. Will throw an exception if [index] is not a group
   * start.
   */
  fun groupSize(index: Int) = groups.groupSize(index)

  /** Get the slot size for [group]. Will throw an exception if [group] is not a group start. */
  fun slotSize(group: Int): Int {
    val start = groups.slotAnchor(group)
    val next = group + 1
    val end = if (next < groupsSize) groups.dataAnchor(next) else slotsSize
    return end - start
  }

  /** Get location the end of the currently started group. */
  val groupEnd
    get() = currentEnd

  /** Get location of the end of the group at [index]. */
  fun groupEnd(index: Int) = index + groups.groupSize(index)

  /** Get the key of the current group. Returns 0 if the [currentGroup] is not a group. */
  val groupKey
    get() =
      if (currentGroup < currentEnd) {
        groups.key(currentGroup)
      } else 0

  /** Get the key of the group at [index]. */
  fun groupKey(index: Int) = groups.key(index)

  /**
   * The group slot index is the index into the current groups slots that will be updated by read
   * by [next].
   */
  val groupSlotIndex
    get() = currentSlot - groups.slotAnchor(parent)

  /** Determine if the group at [index] has an object key */
  fun hasObjectKey(index: Int) = groups.hasObjectKey(index)

  val hasObjectKey: Boolean
    get() = currentGroup < currentEnd && groups.hasObjectKey(currentGroup)

  /** Get the object key for the current group or null if no key was provide */
  val groupObjectKey
    get() = if (currentGroup < currentEnd) groups.objectKey(currentGroup) else null

  /** Get the object key at [index]. */
  fun groupObjectKey(index: Int) = groups.objectKey(index)

  /** Get the current group aux data. */
  val groupAux
    get() = if (currentGroup < currentEnd) groups.aux(currentGroup) else 0

  /** Get the aux data for the group at [index] */
  fun groupAux(index: Int) = groups.aux(index)

  /** Get the node associated with the group if there is one. */
  val groupNode
    get() = if (currentGroup < currentEnd) groups.node(currentGroup) else null

  /** Get the group key at [anchor]. This return 0 if the anchor is not valid. */
  fun groupKey(anchor: Anchor) = if (anchor.valid) groups.key(table.anchorIndex(anchor)) else 0

  /** Returns true when the group at [index] was marked with [SlotWriter.markGroup]. */
  fun hasMark(index: Int) = groups.hasMark(index)

  /**
   * Returns true if the group contains a group, directly or indirectly, that has been marked by a
   * call to [SlotWriter.markGroup].
   */
  fun containsMark(index: Int) = groups.containsMark(index)

  /** Return the number of nodes where emitted into the current group. */
  val parentNodes: Int
    get() = if (parent >= 0) groups.nodeCount(parent) else 0

  /** Return the number of slots left to enumerate with [next]. */
  val remainingSlots
    get(): Int = currentSlotEnd - currentSlot

  /** Return the index of the parent group of the given group */
  fun parentOf(index: Int): Int {
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    requirePrecondition(index >= 0 && index < groupsSize) { "Invalid group index $index" }
    return groups.parentAnchor(index)
  }

  /** Return the number of slots allocated to the [currentGroup] group. */
  val groupSlotCount: Int
    get() {
      val current = currentGroup
      val start = groups.slotAnchor(current)
      val next = current + 1
      val end = if (next < groupsSize) groups.dataAnchor(next) else slotsSize
      return end - start
    }

  /** Get the value stored at [index] in the parent group's slot. */
  fun get(index: Int) =
    (currentSlot + index).let { slotIndex ->
      if (slotIndex < currentSlotEnd) slots[slotIndex] else Composer.Empty
    }

  /** Get the value of the group's slot at [index] for the [currentGroup] group. */
  fun groupGet(index: Int): Any? = groupGet(currentGroup, index)

  /** Get the slot value of the [group] at [index] */
  fun groupGet(group: Int, index: Int): Any? {
    val start = groups.slotAnchor(group)
    val next = group + 1
    val end = if (next < groupsSize) groups.dataAnchor(next) else slotsSize
    val address = start + index
    return if (address < end) slots[address] else Composer.Empty
  }

  /**
   * Get the value of the slot at [currentGroup] or [Composer.Empty] if at then end of a group.
   * During empty mode this value is always [Composer.Empty] which is the value a newly inserted
   * slot.
   */
  fun next(): Any? {
    if (emptyCount > 0 || currentSlot >= currentSlotEnd) {
      hadNext = false
      return Composer.Empty
    }
    hadNext = true
    return slots[currentSlot++]
  }

  /** `true` if the last call to `next()` returned a slot value and [currentSlot] advanced. */
  var hadNext: Boolean = false
    private set

  /**
   * Begin reporting empty for all calls to next() or get(). beginEmpty() can be nested and must
   * be called with a balanced number of endEmpty()
   */
  fun beginEmpty() {
    emptyCount++
  }

  /** End reporting [Composer.Empty] for calls to [next] and [get], */
  fun endEmpty() {
    requirePrecondition(emptyCount > 0) { "Unbalanced begin/end empty" }
    emptyCount--
  }

  /**
   * Close the slot reader. After all [SlotReader]s have been closed the [SlotTable] a
   * [SlotWriter] can be created.
   */
  fun close() {
    closed = true
    table.close(this, sourceInformationMap)
    slots = emptyArray()
  }

  /** Start a group. */
  fun startGroup() {
    if (emptyCount <= 0) {
      val parent = parent
      val currentGroup = currentGroup
      requirePrecondition(groups.parentAnchor(currentGroup) == parent) {
        "Invalid slot table detected"
      }
      sourceInformationMap?.get(anchor(parent))?.reportGroup(table, currentGroup)
      val currentSlotStack = currentSlotStack
      val currentSlot = currentSlot
      val currentEndSlot = currentSlotEnd
      if (currentSlot == 0 && currentEndSlot == 0) {
        currentSlotStack.push(-1)
      } else {
        currentSlotStack.push(currentSlot)
      }
      this.parent = currentGroup
      currentEnd = currentGroup + groups.groupSize(currentGroup)
      this.currentGroup = currentGroup + 1
      this.currentSlot = groups.slotAnchor(currentGroup)
      this.currentSlotEnd =
        if (currentGroup >= groupsSize - 1) slotsSize
        else groups.dataAnchor(currentGroup + 1)
    }
  }

  /** Start a group and validate it is a node group */
  fun startNode() {
    if (emptyCount <= 0) {
      requirePrecondition(groups.isNode(currentGroup)) { "Expected a node group" }
      startGroup()
    }
  }

  /** Skip a group. Must be called at the start of a group. */
  fun skipGroup(): Int {
    runtimeCheck(emptyCount == 0) { "Cannot skip while in an empty region" }
    val count = if (groups.isNode(currentGroup)) 1 else groups.nodeCount(currentGroup)
    currentGroup += groups.groupSize(currentGroup)
    return count
  }

  /** Skip to the end of the current group. */
  fun skipToGroupEnd() {
    runtimeCheck(emptyCount == 0) { "Cannot skip the enclosing group while in an empty region" }
    currentGroup = currentEnd
    currentSlot = 0
    currentSlotEnd = 0
  }

  /** Reposition the read to the group at [index]. */
  fun reposition(index: Int) {
    runtimeCheck(emptyCount == 0) { "Cannot reposition while in an empty region" }
    currentGroup = index
    val parent = if (index < groupsSize) groups.parentAnchor(index) else -1
    if (parent != this.parent) {
      this.parent = parent
      if (parent < 0) this.currentEnd = groupsSize
      else this.currentEnd = parent + groups.groupSize(parent)
      this.currentSlot = 0
      this.currentSlotEnd = 0
    }
  }

  /** Restore the parent to a parent of the current group. */
  fun restoreParent(index: Int) {
    val newCurrentEnd = index + groups.groupSize(index)
    val current = currentGroup
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    runtimeCheck(current >= index && current <= newCurrentEnd) {
      "Index $index is not a parent of $current"
    }
    this.parent = index
    this.currentEnd = newCurrentEnd
    this.currentSlot = 0
    this.currentSlotEnd = 0
  }

  /** End the current group. Must be called after the corresponding [startGroup]. */
  fun endGroup() {
    if (emptyCount == 0) {
      runtimeCheck(currentGroup == currentEnd) {
        "endGroup() not called at the end of a group"
      }
      val parent = groups.parentAnchor(parent)
      this.parent = parent
      currentEnd = if (parent < 0) groupsSize else parent + groups.groupSize(parent)
      val currentSlotStack = currentSlotStack
      val newCurrentSlot = currentSlotStack.pop()
      if (newCurrentSlot < 0) {
        currentSlot = 0
        currentSlotEnd = 0
      } else {
        currentSlot = newCurrentSlot
        currentSlotEnd =
          if (parent >= groupsSize - 1) slotsSize else groups.dataAnchor(parent + 1)
      }
    }
  }

  /**
   * Extract the keys from this point to the end of the group. The current is left unaffected.
   * Must be called inside a group.
   */
  fun extractKeys(): MutableList<KeyInfo> {
    val result = mutableListOf<KeyInfo>()
    if (emptyCount > 0) return result
    var index = 0
    var childIndex = currentGroup
    while (childIndex < currentEnd) {
      result.add(
        KeyInfo(
          groups.key(childIndex),
          groups.objectKey(childIndex),
          childIndex,
          if (groups.isNode(childIndex)) 1 else groups.nodeCount(childIndex),
          index++,
        )
      )
      childIndex += groups.groupSize(childIndex)
    }
    return result
  }

  override fun toString(): String =
    "SlotReader(current=$currentGroup, key=$groupKey, parent=$parent, end=$currentEnd)"

  /** Create an anchor to the current reader location or [index]. */
  fun anchor(index: Int = currentGroup): Anchor =
    table.anchors.getOrAdd(index = index, effectiveSize = groupsSize) { Anchor(location = index) }

  private fun IntArray.node(index: Int) =
    if (isNode(index)) {
      slots[nodeIndex(index)]
    } else Composer.Empty

  private fun IntArray.aux(index: Int) =
    if (hasAux(index)) {
      slots[auxIndex(index)]
    } else Composer.Empty

  private fun IntArray.objectKey(index: Int) =
    if (hasObjectKey(index)) {
      slots[objectKeyIndex(index)]
    } else null
}

/**
 * Information about groups and their keys.
 * 그룹과 그 키에 대한 정보입니다.
 */
internal class KeyInfo(
  /** The group key. */
  val key: Int,

  /** The object key for the group */
  val objectKey: Any?,

  /** The location of the group. */
  val location: Int,

  /**
   * The number of nodes in the group. If the group is a node this is always 1.
   *
   * 그룹에 있는 노드의 개수입니다. 그룹이 노드라면 항상 1입니다.
   */
  val nodes: Int,

  /**
   * The index of the key info in the list returned by extractKeys.
   *
   * extractKeys가 반환한 리스트에서 키 정보의 인덱스입니다.
   */
  val index: Int,
)

/** The writer for a slot table. See [SlotTable] for details. */
internal class SlotWriter(
  /**
   * The [SlotTable] for whom this is writer.
   *
   * 이 writer가 속한 [SlotTable]입니다.
   */
  internal val table: SlotTable,
) {
  /**
   * The gap buffer for groups. This might change as groups are inserted and the array needs to be
   * expanded to account groups. The current valid groups occupy 0 until [groupGapStart] followed
   * [groupGapStart] + [groupGapLen] until `groups.size` where [groupGapStart] until
   * [groupGapStart] + [groupGapLen] is the gap.
   *
   * 그룹을 위한 갭 버퍼입니다. 그룹이 삽입되면 변경될 수 있으며, 배열은 그룹을 수용하기 위해 확장될
   * 수 있습니다. 현재 유효한 그룹은 0부터 [groupGapStart] 전까지와 '[groupGapStart] + [groupGapLen]'부터
   * `groups.size`까지 차지하며, [groupGapStart]부터 '[groupGapStart] + [groupGapLen]'까지가 gap입니다.
   */
  private var groups: IntArray = table.groups

  /**
   * The gap buffer for the slots. This might change as slots are inserted an and the array needs
   * to be expanded to account for the new slots. The current valid slots occupy 0 until
   * [slotsGapStart] and [slotsGapStart] + [slotsGapLen] until `slots.size` where [slotsGapStart]
   * until [slotsGapStart] + [slotsGapLen] is the gap.
   *
   * 슬롯을 위한 갭 버퍼입니다. 슬롯이 삽입되면 변경될 수 있으며, 배열은 새로운 슬롯을 수용하기 위해
   * 확장될 수 있습니다. 현재 유효한 슬롯은 0부터 [slotsGapStart] 전까지와 '[slotsGapStart] + [slotsGapLen]'부터
   * `slots.size`까지 차지하며, [slotsGapStart]부터 '[slotsGapStart] + [slotsGapLen]'까지는 gap입니다.
   */
  private var slots: Array<Any?> = table.slots

  /**
   * A copy of the [SlotTable.anchors] to avoid having to index through [table].
   *
   * [SlotTable.anchors]의 복사본으로, [table]을 통해 인덱싱하지 않도록 하기 위함입니다.
   */
  private var anchors: ArrayList<Anchor> = table.anchors

  /**
   * A copy of [SlotTable.sourceInformationMap] to avoid having to index through [table].
   *
   * [SlotTable.sourceInformationMap]의 복사본으로, [table]을 통해 인덱싱하지 않도록 하기 위함입니다.
   */
  private var sourceInformationMap: HashMap<Anchor, GroupSourceInformation>? = table.sourceInformationMap

  /**
   * A copy of [SlotTable.calledByMap] to avoid having to index through [table].
   *
   * [SlotTable.calledByMap]의 복사본으로, [table]을 통해 인덱싱하지 않도록 하기 위함입니다.
   */
  private var calledByMap: MutableIntObjectMap<MutableIntSet>? = table.calledByMap

  /**
   * Group index of the start of the gap in the groups array.
   *
   * groups 배열에서 gap이 시작되는 그룹 인덱스입니다.
   */
  private var groupGapStart: Int = table.groupsSize

  /**
   * The number of groups in the gap in the groups array.
   *
   * groups 배열에서 gap에 있는 그룹의 개수입니다.
   */
  private var groupGapLen: Int = groups.size / Group_Fields_Size - table.groupsSize

  /**
   * The location of the [slots] array that contains the data for the [parent] group.
   *
   * [slots] 배열에서 [parent] 그룹의 데이터를 포함하는 위치입니다.
   */
  private var currentSlot: Int = 0

  /**
   * The location of the index in [slots] after the slots for the [parent] group.
   *
   * [slots] 배열에서 [parent] 그룹의 슬롯 이후 인덱스 위치입니다.
   */
  private var currentSlotEnd: Int = 0

  /**
   * The is the index of gap in the [slots] array.
   *
   * [slots] 배열에서 gap의 인덱스입니다.
   */
  private var slotsGapStart: Int = table.slotsSize

  /**
   * The number of slots in the gop in the [slots] array.
   *
   * [slots] 배열에서 gap에 있는 슬롯의 개수입니다.
   */
  private var slotsGapLen: Int = slots.size - table.slotsSize

  /**
   * The owner of the gap is the first group that has a end relative index.
   *
   * gap의 소유자는 end relative index를 가진 첫 번째 그룹입니다.
   */
  private var slotsGapOwner: Int = table.groupsSize

  /**
   * The number of times [beginInsert] has been called.
   *
   * [beginInsert]가 호출된 횟수입니다.
   */
  private var insertCount: Int = 0

  /**
   * The number of nodes in the current group. Used to track when nodes are being added and
   * removed in the [parent] group. Once [endGroup] is called, if the nodes count has changed, the
   * containing groups are updated until a node group is encountered.
   *
   * 현재 그룹에 있는 노드의 개수입니다. [parent] 그룹에서 노드가 추가되거나 제거될 때 이를 추적하는 데
   * 사용됩니다. [endGroup]이 호출되었을 때 노드 개수가 변경되었다면, 노드 그룹을 만날 때까지 포함하는
   * 그룹들이 갱신됩니다.
   */
  private var nodeCount: Int = 0

  /**
   * A stack of the groups that have been explicitly started. A group can be implicitly started by
   * using [seek] to seek to indirect child and calling [startGroup] on that child. The groups
   * implicitly started groups will be updated when the [endGroup] is called for the indirect
   * child group.
   *
   * 명시적으로 시작된 그룹들의 스택입니다. 그룹은 [seek]를 사용하여 간접 자식으로 이동한 뒤
   * 해당 자식에 대해 [startGroup]을 호출함으로써 암시적으로 시작될 수도 있습니다. 암시적으로
   * 시작된 그룹들은 간접 자식 그룹에 대해 [endGroup]이 호출될 때 갱신됩니다.
   */
  private val startStack = IntStack()

  /**
   * A stack of the [currentGroupEnd] corresponding with the group is [startStack]. As groups are
   * ended by calling [endGroup], the size of the group might have changed. This stack is a stack
   * of enc group anchors where will reflect the group size change when it is restored by calling
   * [restoreCurrentGroupEnd].
   *
   * [startStack]에 해당하는 그룹의 [currentGroupEnd]를 담는 스택입니다. 그룹은 [endGroup] 호출로
   * 종료되며, 이때 그룹의 크기가 변경될 수 있습니다. 이 스택은 그룹 종료 앵커들의 스택으로,
   * [restoreCurrentGroupEnd]를 호출해 복원할 때 그룹 크기 변경이 반영됩니다.
   */
  private val endStack = IntStack()

  /**
   * This a count of the [nodeCount] of the explicitly started groups.
   *
   * 명시적으로 시작된 그룹들의 [nodeCount] 합계입니다.
   */
  private val nodeCountStack = IntStack()

  /**
   * Deferred slot writes for open groups to avoid thrashing the slot table when slots are added
   * to parent group which already has children.
   *
   * 이미 자식이 있는 부모 그룹에 슬롯이 추가될 때 슬롯 테이블이 불필요하게 뒤섞이는 것을 방지하기
   * 위해, 열린 그룹들에 대해 슬롯 쓰기를 지연시킵니다.
   */
  private var deferredSlotWrites: MutableIntObjectMap<MutableObjectList<Any?>>? = null

  /**
   * The current group that will be started by [startGroup] or skipped by [skipGroup].
   *
   * [startGroup]에 의해 시작되거나 [skipGroup]에 의해 건너뛰어질 현재 그룹입니다.
   */
  var currentGroup: Int = 0
    private set

  /**
   * The index end of the current group.
   *
   * 현재 그룹의 끝 인덱스입니다.
   */
  var currentGroupEnd: Int = table.groupsSize
    private set

  /**
   * True if at the end of a group and an [endGroup] is expected.
   *
   * 그룹의 끝에 도달하여 [endGroup]이 호출되어야 하면 true입니다.
   */
  val isGroupEnd: Boolean
    get() = currentGroup == currentGroupEnd

  val slotsSize: Int
    get() = slots.size - slotsGapLen

  /**
   * Return true if the current slot starts a node. A node is a kind of group so this will return
   * true for isGroup as well.
   *
   * 현재 슬롯이 노드를 시작하면 true를 반환합니다. 노드는 그룹의 한 종류이므로, isGroup인 경우에도
   * true를 반환합니다.
   */
  val isNode: Boolean
    get() =
      currentGroup < currentGroupEnd &&
        groups.isNode(address = groupIndexToAddress(index = currentGroup))

  /**
   * Returns true if the writer is collecting source information.
   *
   * writer가 소스 정보를 수집 중이면 true를 반환합니다.
   */
  val collectingSourceInformation: Boolean
    get() = sourceInformationMap != null

  /**
   * Returns true if the writer is collecting called by information.
   *
   *  writer가 호출자(called by) 정보를 수집 중이면 true를 반환합니다.
   */
  val collectingCalledInformation: Boolean
    get() = calledByMap != null

  /**
   * Return true if the group at [index] is a node.
   *
   * [index]에 있는 그룹이 노드이면 true를 반환합니다.
   */
  fun isNode(index: Int): Boolean =
    groups.isNode(address = groupIndexToAddress(index = index))

  /**
   * return the number of nodes contained in the group at [index].
   *
   * [index]에 있는 그룹에 포함된 노드의 개수를 반환합니다.
   */
  fun nodeCount(index: Int): Int =
    groups.nodeCount(address = groupIndexToAddress(index = index))

  /**
   * Return the key for the group at [index].
   *
   * [index]에 있는 그룹의 키를 반환합니다.
   */
  fun groupKey(index: Int): Int =
    groups.key(address = groupIndexToAddress(index = index))

  /**
   * Return the object key for the group at [index], if it has one, or null if not.
   *
   * [index]에 있는 그룹의 객체 키를 반환합니다. 객체 키가 없으면 null을 반환합니다.
   */
  fun groupObjectKey(index: Int): Any? {
    val address = groupIndexToAddress(index = index)

    return if (groups.hasObjectKey(address = address))
      slots[groups.objectKeyIndex(address = address)]
    else
      null
  }

  /**
   * Return the size of the group at [index].
   *
   * [index]에 있는 그룹의 크기를 반환합니다.
   */
  fun groupSize(index: Int): Int =
    groups.groupSize(address = groupIndexToAddress(index = index))

  /**
   * Return the aux of the group at [index].
   *
   * [index]에 있는 그룹의 aux를 반환합니다.
   */
  fun groupAux(index: Int): Any? {
    val address = groupIndexToAddress(index = index)

    return if (groups.hasAux(address = address))
      slots[groups.auxIndex(address = address)]
    else
      Composer.Empty
  }

  @Suppress("ConvertTwoComparisonsToRangeCheck")
  fun indexInParent(index: Int): Boolean =
    index > parent && index < currentGroupEnd || (parent == 0 && index == 0)

  fun indexInCurrentGroup(index: Int): Boolean =
    indexInGroup(index = index, group = currentGroup)

  fun indexInGroup(index: Int, group: Int): Boolean {
    // If the group is open then the group size in the groups array has not been updated yet
    // so calculate the end from the stored anchor value in the end stack.
    //
    // 그룹이 열려 있다면 groups 배열의 그룹 크기가 아직 갱신되지 않았으므로, end 스택에 저장된
    // 앵커 값에서 끝 위치를 계산합니다.
    val end =
      when {
        group == parent -> currentGroupEnd
        group > startStack.peekOr(default = 0) -> group + groupSize(index = group)
        else -> {
          val openIndex = startStack.indexOf(group)
          when {
            openIndex < 0 -> group + groupSize(index = group)
            else -> (capacity - groupGapLen) - endStack.peek(index = openIndex)
          }
        }
      }

    @Suppress("ConvertTwoComparisonsToRangeCheck")
    return index > group && index < end
  }

  /**
   * Return the node at [index] if [index] is a node group or null.
   *
   * [index]가 노드 그룹이면 해당 노드를 반환하고, 아니면 null을 반환합니다.
   */
  fun node(index: Int): Any? {
    val address = groupIndexToAddress(index = index)

    return if (groups.isNode(address = address))
      slots[dataIndexToDataAddress(dataIndex = groups.nodeIndex(address = address))]
    else
      null
  }

  /**
   * Return the node at [anchor] if it is a node group or null.
   *
   * [anchor]가 노드 그룹이면 해당 노드를 반환하고, 아니면 null을 반환합니다.
   */
  fun node(anchor: Anchor): Any? =
    node(index = anchor.toIndexFor(writer = this))

  /**
   * Return the index of the nearest group that contains [currentGroup].
   *
   * [currentGroup]를 포함하는 가장 가까운 그룹의 인덱스를 반환합니다.
   */
  var parent: Int = -1
    private set

  /**
   * Return the index of the parent for the group at [index].
   *
   * [index]에 있는 그룹의 부모 인덱스를 반환합니다.
   */
  fun parent(index: Int): Int = groups.parent(index = index)

  /**
   * Return the index of the parent for the group referenced by [anchor]. If the anchor
   * is not valid it returns -1.
   *
   * [anchor]가 참조하는 그룹의 부모 인덱스를 반환합니다. 앵커가 유효하지 않으면 -1을
   * 반환합니다.
   */
  fun parent(anchor: Anchor): Int =
    if (anchor.valid)
      groups.parent(index = anchorIndex(anchor = anchor))
    else
      -1

  /**
   * True if the writer has been closed.
   *
   * writer가 닫혔으면 true입니다.
   */
  var closed: Boolean = false
    private set

  /** Close the writer. */
  fun close(normalClose: Boolean) {
    closed = true

    // Ensure, for readers, there is no gap.
    // 리더의 경우 gap이 없도록 보장합니다.
    if (normalClose && startStack.isEmpty()) {
      // Only reset the writer if it closes normally.
      // writer가 정상적으로 종료될 때만 writer를 리셋합니다.
      moveGroupGapTo(index = size)
      moveSlotGapTo(index = slots.size - slotsGapLen, group = groupGapStart)
      clearSlotGap()
      recalculateMarks()
    }

    table.close(
      writer = this,
      groups = groups,
      groupsSize = groupGapStart,
      slots = slots,
      slotsSize = slotsGapStart,
      anchors = anchors,
      sourceInformationMap = sourceInformationMap,
      calledByMap = calledByMap,
    )
  }

  /**
   * Reset the writer to the beginning of the slot table and in the state as if it had just been
   * opened. This differs form closing a writer and opening a new one in that the instance doesn't
   * change and the gap in the slots are not reset to the end of the buffer.
   *
   * writer를 슬롯 테이블의 시작으로 리셋하고, 마치 새로 열렸을 때와 같은 상태로 되돌립니다.
   * 이는 writer를 닫고 새로 여는 것과 달리 인스턴스가 바뀌지 않으며, 슬롯의 gap도 버퍼 끝으로
   * 초기화되지 않습니다.
   */
  fun reset() {
    runtimeCheck(insertCount == 0) { "Cannot reset when inserting" }
    recalculateMarks()
    currentGroup = 0
    currentGroupEnd = size
    currentSlot = 0
    currentSlotEnd = 0
    nodeCount = 0
  }

  /**
   * Set the value of the next slot. Returns the previous value of the slot or
   * [Composer.Empty] is being inserted.
   *
   * 다음 슬롯의 값을 설정합니다. 이전 슬롯의 값을 반환하거나, 새로 삽입되는 경우
   * [Composer.Empty]를 반환합니다.
   */
  fun update(value: Any?): Any? {
    if (insertCount > 0 && currentSlot != slotsGapStart) {
      // Defer write as doing it now would thrash the slot table.
      // 지금 즉시 쓰기를 수행하면 슬롯 테이블이 불필요하게 뒤섞이므로, 쓰기를 지연합니다.
      val deferred =
        (deferredSlotWrites
          ?: (MutableIntObjectMap<MutableObjectList<Any?>>().also { deferredSlotWrites = it }))
          .getOrPut(parent) { MutableObjectList() }
      deferred.add(value)

      return Composer.Empty
    }

    return rawUpdate(value = value)
  }

  private fun rawUpdate(value: Any?): Any? {
    val result = skip()
    set(value = value)
    return result
  }

  /**
   * Append a slot to the [parent] group.
   *
   * [parent] 그룹에 슬롯을 추가합니다.
   */
  fun appendSlot(anchor: Anchor, value: Any?) {
    runtimeCheck(insertCount == 0) {
      // 현재 삽입 중이 아닐 때만 슬롯을 추가할 수 있습니다.
      "Can only append a slot if not current inserting"
    }

    var previousCurrentSlot = currentSlot
    var previousCurrentSlotEnd = currentSlotEnd
    val anchorIndex = anchorIndex(anchor = anchor)
    val slotIndex = groups.dataIndex(address = groupIndexToAddress(index = anchorIndex + 1))

    currentSlot = slotIndex
    currentSlotEnd = slotIndex
    insertSlots(size = 1, group = anchorIndex)

    if (previousCurrentSlot >= slotIndex) {
      previousCurrentSlot++
      previousCurrentSlotEnd++
    }

    slots[slotIndex] = value
    currentSlot = previousCurrentSlot
    currentSlotEnd = previousCurrentSlotEnd
  }

  // trim -> remove
  fun trimTailSlots(count: Int) {
    runtimeCheck(count > 0)

    val parent = parent
    val groupSlotStart = groups.slotIndex(address = groupIndexToAddress(index = parent))
    val groupSlotEnd = groups.dataIndex(address = groupIndexToAddress(index = parent + 1))
    val removeStart = groupSlotEnd - count

    runtimeCheck(removeStart >= groupSlotStart)

    removeSlots(
      start = removeStart,
      len = count,
      group = parent,
    )

    val currentSlot = currentSlot
    if (currentSlot >= groupSlotStart) {
      this.currentSlot = currentSlot - count
    }
  }

  /**
   * Updates the data for the current data group.
   *
   * 현재 데이터 그룹의 데이터를 갱신합니다.
   */
  fun updateAux(value: Any?) {
    val address = groupIndexToAddress(index = currentGroup)

    runtimeCheck(groups.hasAux(address = address)) {
      // 데이터 슬롯으로 생성되지 않은 그룹의 데이터를 갱신하려고 했습니다.
      "Updating the data of a group that was not created with a data slot"
    }

    slots[dataIndexToDataAddress(dataIndex = groups.auxIndex(address = address))] = value
  }

  /**
   * Insert aux data into the parent group. This must be done only after at most one
   * value has been inserted into the slot table for the group.
   *
   * 부모 그룹에 aux 데이터를 삽입합니다. 이는 그룹의 슬롯 테이블에 최대 한 개의
   * 값만 삽입된 이후에만 수행해야 합니다.
   */
  fun insertAux(value: Any?) {
    runtimeCheck(insertCount >= 0) {
      // 삽입 중이 아닐 때는 auxiliary 데이터를 넣을 수 없습니다.
      "Cannot insert auxiliary data when not inserting"
    }

    val parent = parent
    val parentGroupAddress = groupIndexToAddress(index = parent)

    runtimeCheck(!groups.hasAux(address = parentGroupAddress)) {
      // 그룹에 이미 auxiliary 데이터가 있습니다.
      "Group already has auxiliary data"
    }

    insertSlots(size = 1, group = parent)

    val auxIndex = groups.auxIndex(address = parentGroupAddress)
    val auxAddress = dataIndexToDataAddress(dataIndex = auxIndex)

    // 최대 3개의 aux를 지원하는 듯?
    if (currentSlot > auxIndex) {
      // One or more values were inserted into the slot table before the aux value, we need
      // to move them. Currently we only will run into one or two slots (the recompose
      // scope inserted by a restart group and the lambda value in a composableLambda
      // instance) so this is the only case currently supported.
      //
      // aux 값보다 앞서 하나 이상의 값이 슬롯 테이블에 삽입된 경우 이를 이동해야 합니다.
      // 현재는 하나 또는 두 개의 슬롯(restart group이 삽입한 recomposeScope와 composableLambda
      // 인스턴스의 람다 값)만 발생할 수 있으며, 이 경우만 지원됩니다.
      val slotsToMove = currentSlot - auxIndex

      checkPrecondition(slotsToMove < 3) {
        // 두 개를 초과하는 슬롯 이동은 지원하지 않습니다.
        "Moving more than two slot not supported"
      }

      if (slotsToMove > 1) {
        slots[auxAddress + 2] = slots[auxAddress + 1]
      }

      slots[auxAddress + 1] = slots[auxAddress]
    }

    groups.addAux(address = parentGroupAddress)
    slots[auxAddress] = value
    currentSlot++
  }

  fun updateToTableMaps() {
    this.sourceInformationMap = table.sourceInformationMap
    this.calledByMap = table.calledByMap
  }

  private fun groupSourceInformationFor(
    parent: Int,
    sourceInformation: String?,
  ): GroupSourceInformation? =
    sourceInformationMap?.getOrPut(anchor(index = parent)) {
      val result =
        GroupSourceInformation(
          key = 0,
          sourceInformation = sourceInformation,
          dataStartOffset = 0,
        )

      // If we called from a groupless call then the groups added before this call
      // are not reflected in this group information so they need to be added now
      // if they exist.
      //
      // 그룹이 없는 호출에서 실행된 경우, 이 호출 전에 추가된 그룹들은 현재 그룹
      // 정보에 반영되지 않으므로 존재한다면 지금 추가해야 합니다.
      if (sourceInformation == null) {
        var child = parent + 1
        val end = currentGroup

        while (child < end) {
          result.reportGroup(writer = this, group = child)
          child += groups.groupSize(address = child)
        }
      }

      result
    }

  fun recordGroupSourceInformation(sourceInformation: String) {
    if (insertCount > 0) {
      groupSourceInformationFor(parent = parent, sourceInformation = sourceInformation)
    }
  }

  fun recordGrouplessCallSourceInformationStart(key: Int, value: String) {
    if (insertCount > 0) {
      calledByMap?.add(key = key, value = groupKey(index = parent))

      groupSourceInformationFor(parent = parent, sourceInformation = null)
        ?.startGrouplessCall(
          key = key,
          sourceInformation = value,
          dataOffset = currentGroupSlotIndex,
        )
    }
  }

  fun recordGrouplessCallSourceInformationEnd() {
    if (insertCount > 0) {
      groupSourceInformationFor(parent = parent, sourceInformation = null)
        ?.endGrouplessCall(dataOffset = currentGroupSlotIndex)
    }
  }

  /**
   * Updates the node for the current node group to [value].
   *
   * 현재 노드 그룹의 노드를 [value]로 갱신합니다.
   */
  fun updateNode(value: Any?) {
    updateNodeOfGroup(index = currentGroup, value = value)
  }

  /**
   * Update the node of a the group at [anchor] to [value].
   *
   * [anchor]에 있는 그룹의 노드를 [value]로 갱신합니다.
   */
  fun updateNode(anchor: Anchor, value: Any?) {
    updateNodeOfGroup(
      index = anchor.toIndexFor(writer = this),
      value = value,
    )
  }

  /**
   * Updates the node of the parent group.
   *
   * 부모 그룹의 노드를 갱신합니다.
   */
  fun updateParentNode(value: Any?) {
    updateNodeOfGroup(index = parent, value = value)
  }

  /**
   * Set the value at the groups current data slot.
   *
   * 그룹의 현재 데이터 슬롯에 값을 설정합니다.
   */
  fun set(value: Any?) {
    runtimeCheck(currentSlot <= currentSlotEnd) { "Writing to an invalid slot" }
    slots[dataIndexToDataAddress(dataIndex = currentSlot - 1)] = value
  }

  /**
   * Set the group's slot at [index] to [value]. Returns the previous value.
   *
   * 그룹의 [index] 슬롯을 [value]로 설정합니다. 이전 값을 반환합니다.
   */
  inline fun set(index: Int, value: Any?): Any? =
    set(
      group = currentGroup,
      index = index,
      value = value,
    )

  /**
   * Set the [group] slot at [index] to [value]. Returns the previous value.
   *
   * [group]의 [index] 슬롯을 [value]로 설정합니다. 이전 값을 반환합니다.
   */
  fun set(group: Int, index: Int, value: Any?): Any? {
    val slotsIndex = slotIndexOfGroupSlotIndex(group = group, index = index)
    val slotAddress = dataIndexToDataAddress(dataIndex = slotsIndex)
    val previous = slots[slotAddress]

    slots[slotAddress] = value

    return previous
  }

  /**
   * Convert a slot group index into a global slot index.
   *
   * 슬롯 그룹 인덱스를 전역 슬롯 인덱스로 변환합니다.
   */
  // MEMO group의 index를 group 바깥 기준(slots array)의 index로 변환하는 함수?
  fun slotIndexOfGroupSlotIndex(group: Int, index: Int): Int {
    val address = groupIndexToAddress(index = group)
    val slotsStart = groups.slotIndex(address = address)
    val slotsEnd = groups.dataIndex(address = groupIndexToAddress(index = group + 1))
    val slotsIndex = slotsStart + index

    @Suppress("ConvertTwoComparisonsToRangeCheck")
    runtimeCheck(slotsIndex >= slotsStart && slotsIndex < slotsEnd) {
      // 그룹 $group의 잘못된 슬롯 인덱스 $index에 기록하려고 했습니다.
      "Write to an invalid slot index $index for group $group"
    }

    return slotsIndex
  }

  /**
   * Set the slot by index to [Composer.Empty], returning previous value.
   *
   * 인덱스로 지정된 슬롯을 [Composer.Empty]로 설정하고 이전 값을 반환합니다.
   */
  fun clear(slotIndex: Int): Any? {
    val address = dataIndexToDataAddress(dataIndex = slotIndex)
    val previous = slots[address]

    slots[address] = Composer.Empty

    return previous
  }

  /**
   * Skip the current slot without updating. If the slot table is inserting then and
   * [Composer.Empty] slot is added and [skip] return [Composer.Empty].
   *
   * 현재 슬롯을 갱신하지 않고 건너뜁니다. 슬롯 테이블이 삽입 중이라면 [Composer.Empty] 슬롯이
   * 추가되고, [skip]은 [Composer.Empty]를 반환합니다.
   */
  fun skip(): Any? {
    if (insertCount > 0) {
      insertSlots(size = 1, group = parent)
    }

    return slots[dataIndexToDataAddress(dataIndex = currentSlot++)]
  }

  /**
   * Read the [index] slot at the group at [anchor]. Returns [Composer.Empty] if the slot is empty
   * (e.g. out of range).
   *
   * [anchor]의 그룹에서 [index] 슬롯을 읽습니다. 슬롯이 비어 있으면(예: 범위를 벗어난 경우)
   * [Composer.Empty]를 반환합니다.
   */
  fun slot(anchor: Anchor, index: Int): Any? =
    slot(groupIndex = anchorIndex(anchor = anchor), index = index)

  /**
   * Read the [index] slot at the group at index [groupIndex]. Returns [Composer.Empty] if the
   * slot is empty (e.g. out of range).
   *
   * [groupIndex]에 있는 그룹에서 [index] 슬롯을 읽습니다. 슬롯이 비어 있으면(예: 범위를 벗어난 경우)
   * [Composer.Empty]를 반환합니다.
   */
  fun slot(groupIndex: Int, index: Int): Any? {
    val address = groupIndexToAddress(index = groupIndex)
    val slotsStart = groups.slotIndex(address = address)
    val slotsEnd = groups.dataIndex(address = groupIndexToAddress(index = groupIndex + 1))
    val slotsIndex = slotsStart + index

    if (slotsIndex !in slotsStart until slotsEnd) {
      return Composer.Empty
    }

    val slotAddress = dataIndexToDataAddress(dataIndex = slotsIndex)

    return slots[slotAddress]
  }

  /**
   * Call [block] for up to [count] slots values at the end of the group's slots.
   *
   * 그룹의 슬롯 끝에서 최대 [count]개의 슬롯 값에 대해 [block]을 호출합니다.
   */
  inline fun forEachTailSlot(
    groupIndex: Int,
    count: Int,
    block: (slotIndex: Int, slotData: Any?) -> Unit,
  ) {
    val slotsStart = slotsStartIndex(groupIndex = groupIndex)
    val slotsEnd = slotsEndIndex(groupIndex = groupIndex)

    for (slotIndex in max(slotsStart, slotsEnd - count) until slotsEnd) {
      block(slotIndex, slots[dataIndexToDataAddress(dataIndex = slotIndex)])
    }
  }

  /**
   * Return the start index of the slot for [groupIndex]. Used in [forEachTailSlot] to
   * enumerate slots.
   *
   * [groupIndex]의 슬롯 시작 인덱스를 반환합니다. [forEachTailSlot]에서 슬롯을 열거하는 데
   * 사용됩니다.
   */
  internal fun slotsStartIndex(groupIndex: Int): Int =
    groups.slotIndex(address = groupIndexToAddress(index = groupIndex))

  /**
   * Return the end index of the slot for [groupIndex]. Used in [forEachTailSlot] to
   * enumerate slots.
   *
   * [groupIndex]의 슬롯 끝 인덱스를 반환합니다. [forEachTailSlot]에서 슬롯을 열거하는 데
   * 사용됩니다.
   */
  internal fun slotsEndIndex(groupIndex: Int): Int =
    groups.dataIndex(address = groupIndexToAddress(index = groupIndex + 1))

  internal fun slotsEndAllIndex(groupIndex: Int): Int =
    groups.dataIndex(address = groupIndexToAddress(index = groupIndex + groupSize(index = groupIndex)))

  private val currentGroupSlotIndex: Int
    get() = groupSlotIndex(group = parent)

  fun groupSlotIndex(group: Int): Int =
    currentSlot - slotsStartIndex(groupIndex = group) + (deferredSlotWrites?.get(group)?.size ?: 0)

  /**
   * Advance [currentGroup] by [amount]. The [currentGroup] group cannot be advanced outside the
   * currently started [parent].
   *
   * [currentGroup]를 [amount]만큼 전진합니다. [currentGroup] 그룹은 현재 시작된 [parent] 범위를
   * 벗어나 전진할 수 없습니다.
   */
  fun advanceBy(amount: Int) {
    runtimeCheck(amount >= 0) {
      // 뒤로 탐색할 수 없습니다.
      "Cannot seek backwards"
    }

    checkPrecondition(insertCount <= 0) {
      // 삽입 중에는 seek()을 호출할 수 없습니다.
      "Cannot call seek() while inserting"
    }

    if (amount == 0) return

    val index = currentGroup + amount

    @Suppress("ConvertTwoComparisonsToRangeCheck")
    runtimeCheck(index >= parent && index <= currentGroupEnd) {
      // 현재 그룹($parent..$currentGroupEnd) 밖으로는 seek할 수 없습니다.
      "Cannot seek outside the current group ($parent..$currentGroupEnd)"
    }

    this.currentGroup = index

    val newSlot = groups.dataIndex(address = groupIndexToAddress(index = index))

    this.currentSlot = newSlot
    this.currentSlotEnd = newSlot
  }

  /**
   * Seek the current location to [anchor]. The [anchor] must be an anchor to a
   * possibly indirect child of [parent].
   *
   * 현재 위치를 [anchor]로 이동합니다. [anchor]는 [parent]의 간접 자식을 포함할 수
   * 있는 앵커여야 합니다.
   */
  fun seek(anchor: Anchor) {
    advanceBy(amount = anchor.toIndexFor(writer = this) - currentGroup)
  }

  /**
   * Skip to the end of the current group.
   *
   * 현재 그룹의 끝까지 건너뜁니다.
   */
  fun skipToGroupEnd() {
    val newGroup = currentGroupEnd
    currentGroup = newGroup
    currentSlot = groups.dataIndex(address = groupIndexToAddress(index = newGroup))
  }

  /**
   * Begin inserting at the current location. beginInsert() can be nested and must be called with
   * a balanced number of endInsert().
   *
   * 현재 위치에서 삽입을 시작합니다. beginInsert()는 중첩될 수 있으며, 반드시 동일한 횟수의
   * endInsert()와 함께 호출되어야 합니다.
   */
  fun beginInsert() {
    if (insertCount++ == 0) {
      saveCurrentGroupEnd()
    }
  }

  /**
   * Ends inserting.
   *
   * 삽입을 종료합니다.
   */
  fun endInsert() {
    checkPrecondition(insertCount > 0) { "Unbalanced begin/end insert" }

    if (--insertCount == 0) {
      runtimeCheck(nodeCountStack.size == startStack.size) {
        // 삽입 중 startGroup과 endGroup이 일치하지 않습니다.
        "startGroup/endGroup mismatch while inserting"
      }

      restoreCurrentGroupEnd()
    }
  }

  /**
   * Enter the group at current without changing it. Requires not currently inserting.
   *
   * 현재 그룹으로 들어가되 변경하지 않습니다. 삽입 중이 아니어야 합니다.
   */
  fun startGroup() {
    runtimeCheck(insertCount == 0) {
      // 삽입할 때는 반드시 키를 제공해야 합니다.
      "Key must be supplied when inserting"
    }

    startGroup(
      key = 0,
      objectKey = Composer.Empty,
      isNode = false,
      aux = Composer.Empty,
    )
  }

  /**
   * Start a group with a integer key.
   *
   * 정수 키로 그룹을 시작합니다.
   */
  fun startGroup(key: Int) {
    startGroup(
      key = key,
      objectKey = Composer.Empty,
      isNode = false,
      aux = Composer.Empty,
    )
  }

  /**
   * Start a group with a data key.
   *
   * 데이터 키로 그룹을 시작합니다.
   */
  fun startGroup(key: Int, dataKey: Any?) {
    startGroup(
      key = key,
      objectKey = dataKey,
      isNode = false,
      aux = Composer.Empty,
    )
  }

  /**
   * Start a node.
   *
   * 노드를 시작합니다.
   */
  fun startNode(key: Int, objectKey: Any?) {
    startGroup(
      key = key,
      objectKey = objectKey,
      isNode = true,
      aux = Composer.Empty,
    )
  }

  /**
   * Start a node with a aux.
   *
   * aux와 함께 노드를 시작합니다.
   */
  fun startNode(key: Int, objectKey: Any?, node: Any?) {
    startGroup(
      key = key,
      objectKey = objectKey,
      isNode = true,
      aux = node,
    )
  }

  /**
   * Start a data group.
   *
   * 데이터 그룹을 시작합니다.
   */
  fun startData(key: Int, objectKey: Any?, aux: Any?) {
    startGroup(
      key = key,
      objectKey = objectKey,
      isNode = false,
      aux = aux,
    )
  }

  /**
   * Start a data group.
   *
   * 데이터 그룹을 시작합니다.
   */
  fun startData(key: Int, aux: Any?) {
    startGroup(
      key = key,
      objectKey = Composer.Empty,
      isNode = false,
      aux = aux,
    )
  }

  private fun startGroup(key: Int, objectKey: Any?, isNode: Boolean, aux: Any?) {
    val previousParent = parent
    val inserting = insertCount > 0

    nodeCountStack.push(nodeCount)

    currentGroupEnd = // Int
      if (inserting) {
        val current = currentGroup
        val newCurrentSlot = groups.dataIndex(address = groupIndexToAddress(index = current))

        insertGroups(size = 1)

        currentSlot = newCurrentSlot
        currentSlotEnd = newCurrentSlot

        val currentAddress = groupIndexToAddress(index = current)
        val hasObjectKey = objectKey !== Composer.Empty
        val hasAux = !isNode && aux !== Composer.Empty
        val dataAnchor =
          dataIndexToDataAnchor(
            index = newCurrentSlot,
            gapLen = slotsGapLen,
            gapStart = slotsGapStart,
            capacity = slots.size,
          )
            .let { anchor ->
              if (anchor >= 0 && slotsGapOwner < current) {
                // This is a special case where the a parent added slots to its
                // group setting the slotGapOwner back, but no intervening groups
                // contain slots so the slotCurrent is at the beginning fo the gap
                // but is not owned by this group. By definition the beginning of
                // the gap is the index but there are actually two valid anchor
                // values for this location a positive one and a negative (distance
                // from the end of the slot array). In this case moveSlotGapTo() the
                // negative value for all groups after the slotGapOwner so when the
                // gap moves it can adjust the anchors correctly needs the negative
                // anchor.
                //
                // 이 경우는 예외로, 부모가 자신의 그룹에 슬롯을 추가하면서 slotGapOwner가
                // 뒤로 밀렸지만 그 사이의 어떤 그룹도 슬롯을 갖지 않아 slotCurrent가 갭의
                // 시작에 있으면서도 이 그룹의 소유가 아닌 상황을 말합니다. 정의상 갭의
                // 시작은 인덱스이지만, 실제로 이 위치에는 양수와 음수 두 가지 유효한
                // 앵커 값이 존재합니다(음수는 슬롯 배열 끝에서의 거리). 이 경우 moveSlotGapTo()는
                // slotGapOwner 이후 모든 그룹에 음수 값을 사용해야 하며, 그래야 갭 이동 시
                // 앵커를 올바르게 조정할 수 있습니다
                val slotsSize = slots.size - slotsGapLen
                -(slotsSize - anchor + 1)
              } else anchor
            }

        groups.initGroup(
          address = currentAddress,
          key = key,
          isNode = isNode,
          hasObjectKey = hasObjectKey,
          hasAux = hasAux,
          parentAnchor = parent,
          dataAnchor = dataAnchor,
        )

        val dataSlotsNeeded =
          (if (isNode) 1 else 0) + (if (hasObjectKey) 1 else 0) + (if (hasAux) 1 else 0)

        if (dataSlotsNeeded > 0) {
          insertSlots(size = dataSlotsNeeded, group = current)

          val slots = slots
          var currentSlot = currentSlot

          if (isNode) slots[currentSlot++] = aux
          if (hasObjectKey) slots[currentSlot++] = objectKey
          if (hasAux) slots[currentSlot++] = aux

          this.currentSlot = currentSlot
        }

        nodeCount = 0

        val newCurrent = current + 1

        this.parent = current
        this.currentGroup = newCurrent

        if (previousParent >= 0) {
          sourceInformationOf(group = previousParent)
            ?.reportGroup(writer = this, group = current)
        }

        newCurrent
      }

      // inserting == false
      else {
        startStack.push(previousParent)
        saveCurrentGroupEnd()

        val currentGroup = currentGroup
        val currentGroupAddress = groupIndexToAddress(index = currentGroup)

        if (aux != Composer.Empty) {
          if (isNode)
            updateNode(value = aux)
          else
            updateAux(value = aux)
        }

        currentSlot = groups.slotIndex(address = currentGroupAddress)
        currentSlotEnd = groups.dataIndex(address = groupIndexToAddress(this.currentGroup + 1))
        nodeCount = groups.nodeCount(address = currentGroupAddress)

        this.parent = currentGroup
        this.currentGroup = currentGroup + 1

        currentGroup + groups.groupSize(address = currentGroupAddress)
      }
  }

  /**
   * End the current group. Must be called after the corresponding startGroup().
   *
   * 현재 그룹을 종료합니다. 반드시 대응되는 startGroup() 호출 이후에 호출해야 합니다.
   */
  fun endGroup(): Int {
    val inserting = insertCount > 0
    val currentGroup = currentGroup
    val currentGroupEnd = currentGroupEnd

    val groupIndex = parent
    val groupAddress = groupIndexToAddress(index = groupIndex)
    val newNodes = nodeCount
    val newGroupSize = currentGroup - groupIndex
    val isNode = groups.isNode(address = groupAddress)

    if (inserting) {
      // Check for deferred slot writes.
      // 지연된 슬롯 쓰기가 있는지 확인합니다.
      val deferredSlotWrites = deferredSlotWrites

      deferredSlotWrites?.get(groupIndex)?.let { deferredWrites ->
        deferredWrites.forEach { value -> rawUpdate(value = value) }
        deferredSlotWrites.remove(groupIndex)
      }

      // Close the group.
      // 그룹을 닫습니다.
      groups.updateGroupSize(address = groupAddress, value = newGroupSize)
      groups.updateNodeCount(address = groupAddress, value = newNodes)

      nodeCount = nodeCountStack.pop() + if (isNode) 1 else newNodes
      parent = groups.parent(index = groupIndex)

      val nextAddress = if (parent < 0) size else groupIndexToAddress(index = parent + 1)
      val newCurrentSlot = if (nextAddress < 0) 0 else groups.dataIndex(address = nextAddress)

      currentSlot = newCurrentSlot
      currentSlotEnd = newCurrentSlot
    }

    // inserting == false
    else {
      runtimeCheck(currentGroup == currentGroupEnd) {
        // 그룹의 끝에 있어야 합니다.
        "Expected to be at the end of a group"
      }

      // Update group length.
      // 그룹 길이를 갱신합니다.
      val oldGroupSize = groups.groupSize(address = groupAddress)
      val oldNodes = groups.nodeCount(address = groupAddress)

      groups.updateGroupSize(address = groupAddress, value = newGroupSize)
      groups.updateNodeCount(address = groupAddress, value = newNodes)

      val newParent = startStack.pop()

      restoreCurrentGroupEnd()

      this.parent = newParent

      val groupParent = groups.parent(index = groupIndex)

      nodeCount = nodeCountStack.pop()

      if (groupParent == newParent) {
        // The parent group was started we just need to update the node count.
        // 부모 그룹이 시작된 상태이므로 노드 수만 갱신하면 됩니다.
        nodeCount += if (isNode) 0 else newNodes - oldNodes
      } else {
        // If we are closing a group for which startGroup was called after calling
        // seek(). startGroup allows the indirect children to be started. If the group
        // has changed size or the number of nodes it contains the groups between the
        // group being closed and the group that is currently open need to be adjusted.
        // This allows the caller to avoid the overhead of needing to start and end the
        // groups explicitly.
        //
        // seek() 호출 이후에 startGroup이 호출된 그룹을 닫는 경우입니다. startGroup은
        // 간접 자식들을 시작할 수 있게 합니다. 그룹의 크기나 포함된 노드 수가 달라졌다면,
        // 닫히는 그룹과 현재 열려 있는 그룹 사이에 있는 그룹들을 조정해야 합니다. 이렇게
        // 하면 호출자가 그룹들을 일일이 시작하고 종료하는 오버헤드를 피할 수 있습니다.
        val groupSizeDelta = newGroupSize - oldGroupSize
        var nodesDelta = if (isNode) 0 else newNodes - oldNodes

        if (groupSizeDelta != 0 || nodesDelta != 0) {
          var current = groupParent

          while (
            current != 0 &&
            current != newParent &&
            (nodesDelta != 0 || groupSizeDelta != 0)
          ) {
            val currentAddress = groupIndexToAddress(index = current)

            if (groupSizeDelta != 0) {
              val newSize = groups.groupSize(address = currentAddress) + groupSizeDelta
              groups.updateGroupSize(address = currentAddress, value = newSize)
            }

            if (nodesDelta != 0) {
              groups.updateNodeCount(
                address = currentAddress,
                value = groups.nodeCount(address = currentAddress) + nodesDelta,
              )
            }

            if (groups.isNode(address = currentAddress))
              nodesDelta = 0

            current = groups.parent(index = current)
          }
        }

        nodeCount += nodesDelta
      }
    }

    return newNodes
  }

  /**
   * If the start of a group was skipped using [skip], calling [ensureStarted] puts the writer
   * into the same state as if [startGroup] or [startNode] was called on the group starting at
   * [index]. If, after starting, the group, [currentGroup] is not at the end of the group or
   * [currentGroup] is not at the start of a group for which [index] is not location the parent
   * group, an exception is thrown.
   *
   * Calling [ensureStarted] implies that an [endGroup] should be called once the end of the group
   * is reached.
   *
   *
   * [skip]으로 그룹 시작을 건너뛴 경우, [ensureStarted]를 호출하면 writer는 [index]에서 시작하는
   * 그룹에 대해 [startGroup] 또는 [startNode]가 호출된 것과 동일한 상태가 됩니다. 그룹을 시작한 후
   * [currentGroup]이 해당 그룹의 끝에 있지 않거나, [index]가 부모 그룹의 위치가 아닌 그룹의 시작이
   * 아니라면 예외가 발생합니다.
   *
   * [ensureStarted]를 호출하면 그룹의 끝에 도달했을 때 반드시 [endGroup]을 호출해야 함을 의미합니다.
   */
  fun ensureStarted(index: Int) {
    runtimeCheck(insertCount <= 0) {
      // 삽입 중에는 ensureStarted()를 호출할 수 없습니다.
      "Cannot call ensureStarted() while inserting"
    }

    val parent = parent
    if (parent != index) {
      // The new parent a child of the current group.
      // 새 부모는 현재 그룹의 자식입니다.
      @Suppress("ConvertTwoComparisonsToRangeCheck")
      runtimeCheck(index >= parent && index < currentGroupEnd) {
        // $index 위치에서 시작한 그룹은 $parent 그룹의 하위 그룹이어야 합니다.
        "Started group at $index must be a subgroup of the group at $parent"
      }

      val oldCurrent = currentGroup
      val oldCurrentSlot = currentSlot
      val oldCurrentSlotEnd = currentSlotEnd

      currentGroup = index

      startGroup()

      currentGroup = oldCurrent
      currentSlot = oldCurrentSlot
      currentSlotEnd = oldCurrentSlotEnd
    }
  }

  fun ensureStarted(anchor: Anchor) {
    ensureStarted(index = anchor.toIndexFor(writer = this))
  }

  /**
   * Skip the current group. Returns the number of nodes skipped by skipping the group.
   *
   * 현재 그룹을 건너뜁니다. 그룹을 건너뛴 결과 생략된 노드의 개수를 반환합니다.
   */
  fun skipGroup(): Int {
    val groupAddress = groupIndexToAddress(index = currentGroup)
    val newGroup = currentGroup + groups.groupSize(address = groupAddress)

    this.currentGroup = newGroup
    this.currentSlot = groups.dataIndex(address = groupIndexToAddress(index = newGroup))

    return if (groups.isNode(address = groupAddress))
      1
    else
      groups.nodeCount(address = groupAddress)
  }

  /**
   * Remove the current group. Returns if any anchors were in the group removed.
   *
   * 현재 그룹을 제거합니다. 제거된 그룹에 앵커가 있었는지 여부를 반환합니다.
   */
  fun removeGroup(): Boolean {
    runtimeCheck(insertCount == 0) {
      // 삽입 중에는 그룹을 제거할 수 없습니다.
      "Cannot remove group while inserting"
    }

    val oldGroup = currentGroup
    val oldSlot = currentSlot
    val dataStart = groups.dataIndex(address = groupIndexToAddress(index = oldGroup))
    val count = skipGroup()

    // Remove the group from its parent information.
    // 그룹을 부모 정보에서 제거합니다.
    sourceInformationOf(group = parent)?.let { sourceInformation ->
      tryAnchor(group = oldGroup)?.let { anchor -> sourceInformation.removeAnchor(anchor = anchor) }
    }

    // Remove any recalculate markers ahead of this delete as they are in the group
    // that is being deleted.
    //
    // 삭제되는 그룹 안에 있으므로, 이 삭제보다 앞서 있는 재계산 마커들을 제거합니다.
    pendingRecalculateMarks?.let { marks ->
      while (marks.isNotEmpty() && marks.peek() >= oldGroup) {
        marks.takeMax()
      }
    }

    val anchorsRemoved =
      removeGroups(start = oldGroup, len = currentGroup - oldGroup)

    removeSlots(
      start = dataStart,
      len = currentSlot - dataStart,
      group = oldGroup - 1,
    )

    currentGroup = oldGroup
    currentSlot = oldSlot
    nodeCount -= count

    return anchorsRemoved
  }

  /**
   * Returns an iterator for all the slots of group and all the children of the group.
   *
   * 그룹과 그 자식 그룹의 모든 슬롯을 순회하는 iterator를 반환합니다.
   */
  fun groupSlots(): Iterator<Any?> {
    val start = groups.dataIndex(address = groupIndexToAddress(index = currentGroup))
    val end =
      groups.dataIndex(
        address = groupIndexToAddress(
          index = currentGroup + groupSize(index = currentGroup),
        ),
      )

    return object : Iterator<Any?> {
      var current: Int = start

      override fun hasNext(): Boolean = current < end

      override fun next(): Any? =
        if (hasNext()) slots[dataIndexToDataAddress(current++)] else null
    }
  }

  @Suppress("unused")
  inline fun forAllData(group: Int, block: (index: Int, data: Any?) -> Unit) {
    val address = groupIndexToAddress(index = group)
    val start = groups.dataIndex(address = address)
    val end =
      groups.dataIndex(
        address = groupIndexToAddress(
          index = currentGroup + groupSize(index = currentGroup),
        ),
      )

    for (slot in start until end) {
      block(slot, slots[dataIndexToDataAddress(dataIndex = slot)])
    }
  }

  inline fun traverseGroupAndChildren(
    group: Int,
    enter: (child: Int) -> Unit,
    exit: (child: Int) -> Unit,
  ) {
    var current = group
    var currentParent = parent(index = current)
    val size = size
    val end = group + groupSize(index = group)

    while (current < end) {
      enter(current)

      val next = current + 1
      val nextParent = if (next < size) parent(index = next) else -1

      if (nextParent != current) {
        while (true) {
          exit(current)

          if (current == group) break
          if (currentParent == nextParent) break

          current = currentParent
          currentParent = parent(index = current)
        }
      }

      current = next
      currentParent = nextParent
    }
  }

  fun forAllDataInRememberOrder(group: Int, block: (index: Int, data: Any?) -> Unit) {
    // The list and set implement a multi-map of groups to slots that need to be emitted
    // after group. The a multi-map itself is not used as a generic multi map would box the
    // integers and otherwise allocate more memory.
    //
    // list와 set은 그룹 이후에 출력해야 하는 슬롯을 매핑하는 멀티맵을 구현합니다.
    // 일반적인 멀티맵은 정수를 boxing하고 불필요한 메모리를 할당하기 때문에 사용하지
    // 않습니다.
    var deferredSlotIndexes: MutableIntList? = null
    var deferredAfters: MutableIntSet? = null

    traverseGroupAndChildren(
      group = group,
      enter = { child ->
        for (slotIndex in dataIndex(index = child) until dataIndex(index = child + 1)) {
          val address = dataIndexToDataAddress(dataIndex = slotIndex)
          val value = slots[address]

          if (value is RememberObserverHolder) {
            val after = value.after
            if (after != null && after.valid) {
              // If the data is a remember holder that has an anchor, it must be
              // emitted after the group it is anchored so defer it now.
              //
              // 데이터가 앵커를 가진 remember 홀더라면 앵커된 그룹 뒤에 출력해야
              // 하므로 지금은 지연합니다.
              val index = anchorIndex(anchor = after)
              val afters = deferredAfters ?: mutableIntSetOf().also { deferredAfters = it }
              val slots = deferredSlotIndexes ?: mutableIntListOf().also { deferredSlotIndexes = it }

              afters.add(index)
              slots.add(index)
              slots.add(slotIndex)

              continue
            }
          }

          block(slotIndex, value)
        }
      },
      exit = { child ->
        val slotIndexes = deferredSlotIndexes
        val afters = deferredAfters

        if (slotIndexes != null && afters != null && afters.remove(child)) {
          var expected = 0
          val size = slotIndexes.size

          repeat(size / 2) {
            val start = it * 2
            val after = slotIndexes[start]

            if (after == child) {
              val slotIndex = slotIndexes[start + 1]
              val data = slots[dataIndexToDataAddress(dataIndex = slotIndex)]

              block(slotIndex, data)
            } else {
              // This pattern removes the group from the list while
              // enumerating following a removeIf style pattern. We cannot
              // use removeIf directly the int array stores an inline pair of
              // the after group index and the slot index.
              //
              // 이 패턴은 removeIf 스타일로 열거하는 동안 리스트에서 그룹을
              // 제거합니다. int 배열이 after 그룹 인덱스와 슬롯 인덱스의
              // 인라인 쌍을 저장하므로 removeIf를 직접 사용할 수 없습니다.
              if (start != expected) {
                slotIndexes[expected++] = after
                slotIndexes[expected++] = slotIndexes[start + 1]
              } else expected += 2
            }
          }

          if (expected != size) {
            slotIndexes.removeRange(start = expected, end = size)
          }
        }
      },
    )
  }

  /**
   * Move the group at [offset] groups after [currentGroup] to be in front of [currentGroup].
   * After this completes, the moved group will be the current group. [offset] must less than the
   * number of groups after the [currentGroup] left in the [parent] group.
   *
   * [currentGroup] 뒤 [offset]번째 그룹을 [currentGroup] 앞으로 이동합니다. 완료되면 이동된
   * 그룹이 현재 그룹이 됩니다. [offset]은 [parent] 그룹에서 [currentGroup] 뒤에 남아 있는
   * 그룹 수보다 작아야 합니다.
   */
  fun moveGroup(offset: Int) {
    runtimeCheck(insertCount == 0) {
      // 삽입 중에는 그룹을 이동할 수 없습니다.
      "Cannot move a group while inserting"
    }
    runtimeCheck(offset >= 0) {
      // 매개변수 offset이 범위를 벗어났습니다.
      "Parameter offset is out of bounds"
    }

    if (offset == 0) return

    val current = currentGroup
    val parent = parent
    val parentEnd = currentGroupEnd

    // Find the group to move.
    // 이동할 그룹을 찾습니다.
    var count = offset
    var groupToMove = current

    while (count > 0) {
      groupToMove += groups.groupSize(address = groupIndexToAddress(index = groupToMove))

      runtimeCheck(groupToMove <= parentEnd) {
        // 매개변수 offset이 범위를 벗어났습니다.
        "Parameter offset is out of bounds"
      }

      count--
    }

    val moveLen = groups.groupSize(address = groupIndexToAddress(index = groupToMove))
    val destinationSlot = groups.dataIndex(address = groupIndexToAddress(index = currentGroup))
    val dataStart = groups.dataIndex(address = groupIndexToAddress(index = groupToMove))
    val dataEnd = groups.dataIndex(address = groupIndexToAddress(index = groupToMove + moveLen))
    val moveDataLen = dataEnd - dataStart

    // The order of operations is important here. Moving a block in the array requires,
    //
    //   1) inserting space for the block
    //   2) copying the block
    //   3) deleting the previous block
    //
    // Inserting into a gap buffer requires moving the gap to the location of the insert and
    // then shrinking the gap. Moving the gap in the slot table requires updating all anchors
    // in the group table that refer to locations that changed by the gap move. For this to
    // work correctly, the groups must be in a valid state. That requires that the slot table
    // must be inserted into first so there are no transitory constraint violations in the
    // groups (that is, there are no invalid, duplicate or out of order anchors). Conversely,
    // removing slots also involves moving the gap requiring the groups to be valid so the
    // slots must be removed after the groups that reference the old locations are removed.
    // So the order of operations when moving groups is,
    //
    //   1) insert space for the slots at the destination (must be first)
    //   2) insert space for the groups at the destination
    //   3) copy the groups to their new location
    //   4) copy the slots to their new location
    //   5) fix-up the moved group anchors to refer to the new slot locations
    //   6) update any anchors in the group being moved
    //   7) remove the old groups
    //   8) fix parent anchors
    //   9) remove the old slots (must be last)
    //
    //
    //
    // 여기는 연산 순서가 중요합니다. 배열에서 블록을 이동하려면,
    //
    //   1. 블록을 위한 공간을 삽입
    //   2. 블록을 복사
    //   3. 이전 블록을 삭제
    //
    // gap 버퍼에 삽입하려면 삽입 위치로 gap을 이동한 후 gap을 줄여야 합니다. 슬롯 테이블에서
    // gap을 이동시키면 gap 이동으로 위치가 바뀐 곳을 참조하는 그룹 테이블의 모든 앵커를
    // 갱신해야 합니다. 이것이 올바르게 동작하려면 그룹이 유효한 상태여야 합니다. 따라서
    // 먼저 슬롯 테이블에 삽입이 이뤄져야 하며, 그렇게 하면 그룹에 임시로 잘못된 상태(잘못된,
    // 중복된, 순서가 어긋난 앵커)가 생기지 않습니다. 반대로 슬롯 제거도 gap 이동을 수반하므로
    // 그룹이 유효해야 하며, 따라서 이전 위치를 참조하는 그룹들이 제거된 후에 슬롯을 제거해야
    // 합니다. 따라서 그룹을 이동할 때 연산 순서는 다음과 같습니다.
    //
    //   1. 대상 위치에 슬롯을 위한 공간 삽입 (반드시 먼저)
    //   2. 대상 위치에 그룹을 위한 공간 삽입
    //   3. 그룹을 새 위치로 복사
    //   4. 슬롯을 새 위치로 복사
    //   5. 이동된 그룹 앵커를 새 슬롯 위치를 참조하도록 수정
    //   6. 이동된 그룹 내의 앵커 갱신
    //   7. 이전 그룹 제거
    //   8. 부모 앵커 수정
    //   9. 이전 슬롯 제거 (반드시 마지막)

    // 1) insert space for the slots at the destination (must be first)
    // 1. 대상 위치에 슬롯을 위한 공간 삽입 (반드시 먼저)
    insertSlots(size = moveDataLen, group = max(currentGroup - 1, 0))

    // 2) insert space for the groups at the destination
    // 2. 대상 위치에 그룹을 위한 공간 삽입
    insertGroups(size = moveLen)

    // 3) copy the groups to their new location
    // 3. 그룹을 새 위치로 복사
    val groups = groups
    val moveLocationAddress = groupIndexToAddress(index = groupToMove + moveLen)
    val moveLocationOffset = moveLocationAddress * Group_Fields_Size
    val currentAddress = groupIndexToAddress(index = current)

    groups.copyInto(
      destination = groups,
      destinationOffset = currentAddress * Group_Fields_Size,
      startIndex = moveLocationOffset,
      endIndex = moveLocationOffset + moveLen * Group_Fields_Size,
    )

    // 4) copy the slots to their new location
    // 4. 슬롯을 새 위치로 복사
    if (moveDataLen > 0) {
      val slots = slots
      slots.fastCopyInto(
        destination = slots,
        destinationOffset = destinationSlot,
        startIndex = dataIndexToDataAddress(dataIndex = dataStart + moveDataLen),
        endIndex = dataIndexToDataAddress(dataIndex = dataEnd + moveDataLen),
      )
    }

    // 5) fix-up the moved group anchors to refer to the new slot locations
    // 5. 이동된 그룹 앵커를 새 슬롯 위치를 참조하도록 수정
    val dataMoveDistance = (dataStart + moveDataLen) - destinationSlot
    val slotsGapStart = slotsGapStart
    val slotsGapLen = slotsGapLen
    val slotsCapacity = slots.size
    val slotsGapOwner = slotsGapOwner

    for (group in current until current + moveLen) {
      val groupAddress = groupIndexToAddress(index = group)
      val oldIndex = groups.dataIndex(address = groupAddress)
      val newIndex = oldIndex - dataMoveDistance
      val newAnchor =
        dataIndexToDataAnchor(
          index = newIndex,
          gapStart = if (slotsGapOwner < groupAddress) 0 else slotsGapStart,
          gapLen = slotsGapLen,
          capacity = slotsCapacity,
        )

      groups.updateDataIndex(address = groupAddress, dataIndex = newAnchor)
    }

    // 6) update any anchors in the group being moved
    // 6. 이동된 그룹 내의 앵커 갱신
    moveAnchors(
      originalLocation = groupToMove + moveLen,
      newLocation = current,
      size = moveLen,
    )

    // 7) remove the old groups
    // 7. 이전 그룹 제거
    val anchorsRemoved =
      removeGroups(
        start = groupToMove + moveLen,
        len = moveLen,
      )

    runtimeCheck(!anchorsRemoved) {
      // 예상치 않게 앵커가 제거되었습니다.
      "Unexpectedly removed anchors"
    }

    // 8) fix parent anchors
    // 8. 부모 앵커 수정
    fixParentAnchorsFor(
      parent = parent,
      endGroup = currentGroupEnd,
      firstChild = current,
    )

    // 9) remove the old slots (must be last)
    // 9. 이전 슬롯 제거 (반드시 마지막)
    if (moveDataLen > 0) {
      removeSlots(
        start = dataStart + moveDataLen,
        len = moveDataLen,
        group = groupToMove + moveLen - 1,
      )
    }
  }

  companion object {
    private fun moveGroup(
      fromWriter: SlotWriter,
      fromIndex: Int,
      toWriter: SlotWriter,
      updateFromCursor: Boolean,
      updateToCursor: Boolean,
      removeSourceGroup: Boolean = true,
    ): List<Anchor> {
      val groupsToMove = fromWriter.groupSize(index = fromIndex)
      val sourceGroupsEnd = fromIndex + groupsToMove
      val sourceSlotsStart = fromWriter.dataIndex(index = fromIndex)
      val sourceSlotsEnd = fromWriter.dataIndex(index = sourceGroupsEnd)
      val slotsToMove = sourceSlotsEnd - sourceSlotsStart
      val hasMarks = fromWriter.containsAnyGroupMarks(group = fromIndex)

      // Make room in the slot table.
      // 슬롯 테이블에 공간을 마련합니다.
      toWriter.insertGroups(size = groupsToMove)
      toWriter.insertSlots(size = slotsToMove, group = toWriter.currentGroup)

      // If either from gap is before the move, move the gap after the move to simplify
      // the logic of this method.
      //
      // from 쪽의 gap이 이동 대상보다 앞에 있으면, 이 메서드의 로직을 단순화하기 위해
      // 이동 이후 위치로 gap을 옮깁니다.
      if (fromWriter.groupGapStart < sourceGroupsEnd) {
        fromWriter.moveGroupGapTo(index = sourceGroupsEnd)
      }
      if (fromWriter.slotsGapStart < sourceSlotsEnd) {
        fromWriter.moveSlotGapTo(index = sourceSlotsEnd, group = sourceGroupsEnd)
      }

      // Copy the groups and slots.
      // 그룹과 슬롯을 복사합니다.
      val groups = toWriter.groups
      val currentGroup = toWriter.currentGroup

      fromWriter.groups.copyInto(
        destination = groups,
        destinationOffset = currentGroup * Group_Fields_Size,
        startIndex = fromIndex * Group_Fields_Size,
        endIndex = sourceGroupsEnd * Group_Fields_Size,
      )

      val slots = toWriter.slots
      val currentSlot = toWriter.currentSlot

      fromWriter.slots.fastCopyInto(
        destination = slots,
        destinationOffset = currentSlot,
        startIndex = sourceSlotsStart,
        endIndex = sourceSlotsEnd,
      )

      // Fix the parent anchors and data anchors. This would read better as two loops but
      // conflating the loops has better locality of reference.
      //
      // 부모 앵커와 데이터 앵커를 수정합니다. 두 개의 루프로 나누는 편이 읽기에는 더 좋지만,
      // 하나로 합치는 것이 참조 지역성을 더 좋게 합니다.
      val parent = toWriter.parent

      groups.updateParentAnchor(address = currentGroup, value = parent)

      val parentDelta = currentGroup - fromIndex
      val moveEnd = currentGroup + groupsToMove
      val dataIndexDelta = currentSlot - with(toWriter) { groups.dataIndex(address = currentGroup) }
      var slotsGapOwner = toWriter.slotsGapOwner
      val slotsGapLen = toWriter.slotsGapLen
      val slotsCapacity = slots.size

      for (groupAddress in currentGroup until moveEnd) {
        // Update the parent anchor, the first group has already been set.
        // 부모 앵커를 갱신합니다. 첫 번째 그룹은 이미 설정되어 있습니다.
        if (groupAddress != currentGroup) {
          val previousParent = groups.parentAnchor(address = groupAddress)
          groups.updateParentAnchor(address = groupAddress, value = previousParent + parentDelta)
        }

        val newDataIndex = with(toWriter) { groups.dataIndex(address = groupAddress) + dataIndexDelta }
        val newDataAnchor =
          with(toWriter) {
            dataIndexToDataAnchor(
              index = newDataIndex,
              // Ensure that if the slotGapOwner is below groupAddress we get an end
              // relative anchor.
              //
              // slotGapOwner가 groupAddress보다 아래에 있으면 끝 기준 앵커가 되도록
              // 보장합니다.
              gapStart = if (slotsGapOwner < groupAddress) 0 else slotsGapStart,
              gapLen = slotsGapLen,
              capacity = slotsCapacity,
            )
          }

        // Update the data index.
        // 데이터 인덱스를 갱신합니다.
        groups.updateDataAnchor(address = groupAddress, anchor = newDataAnchor)

        // Move the slotGapOwner if necessary.
        // 필요하다면 slotGapOwner를 이동합니다.
        if (groupAddress == slotsGapOwner) slotsGapOwner++
      }

      toWriter.slotsGapOwner = slotsGapOwner

      // Extract the anchors in range.
      // 해당 범위의 앵커를 추출합니다.
      val startAnchors = fromWriter.anchors.anchorLocationOf(index = fromIndex, effectiveSize = fromWriter.size)
      val endAnchors = fromWriter.anchors.anchorLocationOf(index = sourceGroupsEnd, effectiveSize = fromWriter.size)
      val anchors =
        if (startAnchors < endAnchors) {
          val sourceAnchors = fromWriter.anchors
          val anchors = ArrayList<Anchor>(/* initialCapacity = */ endAnchors - startAnchors)

          // update the anchor locations to their new location.
          // 앵커 위치를 새로운 위치로 갱신합니다.
          val anchorDelta = currentGroup - fromIndex

          for (anchorIndex in startAnchors until endAnchors) {
            val sourceAnchor = sourceAnchors[anchorIndex]
            sourceAnchor.location += anchorDelta
            anchors.add(sourceAnchor)
          }

          // Insert them into the new table.
          // 새 테이블에 삽입합니다.
          val insertLocation =
            toWriter.anchors.anchorLocationOf(index = toWriter.currentGroup, effectiveSize = toWriter.size)

          toWriter.anchors.addAll(insertLocation, anchors)

          // Remove them from the old table.
          // 이전 테이블에서 제거합니다.
          sourceAnchors.subList(startAnchors, endAnchors).clear()

          anchors
        }

        // startAnchors >= endAnchors
        else emptyList()

      // Move any source information from the source table to the destination table.
      // 원본 테이블의 소스 정보를 대상 테이블로 이동합니다.
      if (anchors.isNotEmpty()) {
        val sourceSourceInformationMap = fromWriter.sourceInformationMap
        val destinationSourceInformation = toWriter.sourceInformationMap

        if (sourceSourceInformationMap != null && destinationSourceInformation != null) {
          anchors.fastForEach { anchor ->
            val information = sourceSourceInformationMap[anchor]
            if (information != null) {
              sourceSourceInformationMap.remove(anchor)
              destinationSourceInformation[anchor] = information
            }
          }
        }
      }

      // Record the new group in the parent information.
      // 부모 정보에 새로운 그룹을 기록합니다.
      val toWriterParent = toWriter.parent

      toWriter.sourceInformationOf(group = parent)?.let { sourceInformation ->
        var predecessor = -1
        var child = toWriterParent + 1
        val endGroup = toWriter.currentGroup

        while (child < endGroup) {
          predecessor = child
          child += toWriter.groups.groupSize(address = child)
        }

        sourceInformation.addGroupAfter(
          writer = toWriter,
          predecessor = predecessor,
          group = endGroup,
        )
      }

      val parentGroup = fromWriter.parent(index = fromIndex)
      val anchorsRemoved =
        if (!removeSourceGroup) {
          // e.g.: we can skip groups removal for insertTable of Composer because
          // it's going to be disposed anyway after changes applied.
          //
          // 예: Composer의 insertTable에서는 그룹 제거를 건너뛸 수 있습니다.
          // change가 적용된 후 어차피 폐기되기 때문입니다.
          false
        } else if (updateFromCursor) {
          // Remove the group using the sequence the writer expects when removing a group,
          // that is the root group and the group's parent group must be correctly started and
          // ended when it is not a root group.
          //
          // 작성자가 그룹을 제거할 때 기대하는 순서로 그룹을 제거합니다. 즉, 루트 그룹이 아니면
          // 루트 그룹과 해당 그룹의 부모 그룹이 올바르게 시작되고 종료되어야 합니다.
          val needsStartGroups = parentGroup >= 0
          if (needsStartGroups) {
            // If we are not a root group then we are removing from a group so ensure
            // the root group is started and then seek to the parent group and start it.
            //
            // 루트 그룹이 아니라면 다른 그룹에서 제거하는 것이므로, 루트 그룹이 시작되었는지
            // 확인한 뒤 부모 그룹으로 이동하여 부모 그룹을 시작해야 합니다.
            fromWriter.startGroup()
            fromWriter.advanceBy(amount = parentGroup - fromWriter.currentGroup)
            fromWriter.startGroup()
          }

          fromWriter.advanceBy(amount = fromIndex - fromWriter.currentGroup)

          val anchorsRemoved = fromWriter.removeGroup()
          if (needsStartGroups) {
            fromWriter.skipToGroupEnd()
            fromWriter.endGroup()
            fromWriter.skipToGroupEnd()
            fromWriter.endGroup()
          }

          anchorsRemoved
        } else {
          // Remove the group directly instead of using cursor operations.
          // 커서 연산을 사용하지 않고 직접 그룹을 제거합니다.
          val anchorsRemoved = fromWriter.removeGroups(start = fromIndex, len = groupsToMove)

          fromWriter.removeSlots(
            start = sourceSlotsStart,
            len = slotsToMove,
            group = fromIndex - 1,
          )

          anchorsRemoved
        }

      // Ensure we correctly do not remove anchors with the above delete.
      // 위의 삭제 과정에서 앵커가 잘못 제거되지 않았는지 확인합니다.
      runtimeCheck(!anchorsRemoved) {
        // 예상치 않게 앵커가 제거되었습니다.
        "Unexpectedly removed anchors"
      }

      // Update the node count in the toWriter.
      // toWriter 안에서 노드 개수를 갱신합니다.
      toWriter.nodeCount +=
        if (groups.isNode(address = currentGroup)) 1 else groups.nodeCount(address = currentGroup)

      // Move the toWriter's currentGroup passed the insert.
      // toWriter의 currentGroup을 삽입이 끝난 위치로 이동합니다.
      if (updateToCursor) {
        toWriter.currentGroup = currentGroup + groupsToMove
        toWriter.currentSlot = currentSlot + slotsToMove
      }

      // If the group being inserted has marks then update the toWriter's parent marks.
      // 삽입되는 그룹에 mark가 있으면 toWriter의 부모 mark를 갱신합니다.
      if (hasMarks) {
        toWriter.updateContainsMark(group = parent)
      }

      return anchors
    }
  }

  /**
   * Move (insert then delete) the group at [anchor] group into the current insert location of
   * [writer]. All anchors in the group are moved into the slot table of [writer]. [anchor] must
   * be a group contained in the current started group.
   *
   * This requires [writer] be inserting and this writer to not be inserting.
   *
   *
   * [anchor] 그룹을 [writer]의 현재 삽입 위치로 이동합니다(삽입 후 삭제). 그룹에 있는 모든
   * 앵커를 [writer]의 슬롯 테이블로 옮깁니다. [anchor]는 현재 시작된 그룹에 포함된 그룹이어야
   * 합니다.
   *
   * 이를 위해 [writer]는 삽입 중이어야 하고, 이 writer는 삽입 중이 아니어야 합니다.
   */
  fun moveTo(anchor: Anchor, offset: Int, writer: SlotWriter): List<Anchor> {
    runtimeCheck(writer.insertCount > 0)
    runtimeCheck(insertCount == 0)
    runtimeCheck(anchor.valid)

    val location = anchorIndex(anchor = anchor) + offset
    val currentGroup = currentGroup

    runtimeCheck(location in currentGroup until currentGroupEnd)

    val parent = parent(index = location)
    val size = groupSize(index = location)
    val nodes = if (isNode(index = location)) 1 else nodeCount(index = location)
    val result =
      moveGroup(
        fromWriter = this,
        fromIndex = location,
        toWriter = writer,
        updateFromCursor = false,
        updateToCursor = false,
      )

    updateContainsMark(group = parent)

    // Fix group sizes and node counts from the parent of the moved group to the current group.
    // 이동된 그룹의 부모부터 현재 그룹까지 그룹 크기와 노드 수를 수정합니다.
    var current = parent
    var updatingNodes = nodes > 0

    while (current >= currentGroup) {
      val currentAddress = groupIndexToAddress(index = current)
      groups.updateGroupSize(
        address = currentAddress,
        value = groups.groupSize(address = currentAddress) - size,
      )

      if (updatingNodes) {
        if (groups.isNode(address = currentAddress))
          updatingNodes = false
        else
          groups.updateNodeCount(
            address = currentAddress,
            value = groups.nodeCount(address = currentAddress) - nodes,
          )
      }

      current = parent(index = current)
    }

    if (updatingNodes) {
      runtimeCheck(nodeCount >= nodes)
      nodeCount -= nodes
    }

    return result
  }

  /**
   * Move (insert and then delete) the group at [index] from [slots]. All anchors in the range
   * (including [index]) are moved to the slot table for which this is a reader.
   *
   * It is required that the writer be inserting.
   *
   * @return a list of the anchors that were moved.
   *
   *
   * [index] 위치의 그룹을 [slots]에서 이동합니다(삽입 후 삭제). 해당 범위([index] 포함)의
   * 모든 앵커를 이 리더가 참조하는 슬롯 테이블로 옮깁니다.
   *
   * writer는 삽입 중이어야 합니다.
   *
   * @return 이동된 앵커들의 목록을 반환합니다.
   */
  fun moveFrom(table: SlotTable, index: Int, removeSourceGroup: Boolean = true): List<Anchor> {
    runtimeCheck(insertCount > 0)

    if (
      index == 0 &&
      currentGroup == 0 &&
      this.table.groupsSize == 0 &&
      table.groups.groupSize(address = index) == table.groupsSize
    ) {
      // Special case for moving the entire slot table into an empty table. This case occurs
      // during initial composition.
      //
      // 빈 테이블로 전체 슬롯 테이블을 이동하는 특수 사례입니다. 이는 초기 컴포지션 동안
      // 발생합니다.
      val myGroups = groups
      val mySlots = slots
      val myAnchors = anchors
      val mySourceInformation = sourceInformationMap
      val myCallInformation = calledByMap
      val groups = table.groups
      val groupsSize = table.groupsSize
      val slots = table.slots
      val slotsSize = table.slotsSize
      val sourceInformation = table.sourceInformationMap
      val callInformation = table.calledByMap

      this.groups = groups
      this.slots = slots
      this.anchors = table.anchors
      this.groupGapStart = groupsSize
      this.groupGapLen = groups.size / Group_Fields_Size - groupsSize
      this.slotsGapStart = slotsSize
      this.slotsGapLen = slots.size - slotsSize
      this.slotsGapOwner = groupsSize
      this.sourceInformationMap = sourceInformation
      this.calledByMap = callInformation

      table.setTo(
        groups = myGroups,
        groupsSize = 0,
        slots = mySlots,
        slotsSize = 0,
        anchors = myAnchors,
        sourceInformationMap = mySourceInformation,
        calledByMap = myCallInformation,
      )

      return this.anchors
    }

    return table.write { writer ->
      moveGroup(
        fromWriter = writer,
        fromIndex = index,
        toWriter = this,
        updateFromCursor = true,
        updateToCursor = true,
        removeSourceGroup = removeSourceGroup,
      )
    }
  }

  /**
   * Replace the key of the current group with one that will not match its current value which
   * will cause the composer to discard it and rebuild the content.
   *
   * This is used during live edit when the function that generated the content has been changed
   * and the slot table information does not match the expectations of the new code. This is done
   * conservatively in that any change in the code is assume to make the state stored in the table
   * incompatible.
   *
   *
   * 현재 그룹의 키를 기존 값과 일치하지 않는 키로 바꿉니다. 그러면 컴포저가 이를 버리고 내용을
   * 다시 빌드합니다.
   *
   * LiveEdit 중, 콘텐츠를 생성한 함수가 변경되어 슬롯 테이블 정보가 새 코드의 기대와 맞지 않을 때
   * 사용합니다. 보수적으로 처리하여, 코드의 어떤 변경도 테이블에 저장된 상태를 호환 불가로 만든다고
   * 가정합니다.
   */
  fun bashCurrentGroup() {
    groups.updateGroupKey(address = currentGroup, key = LIVE_EDIT_INVALID_KEY)
  }

  /**
   * Insert the group at [index] in [table] to be the content of [currentGroup] plus [offset]
   * without moving [currentGroup].
   *
   * It is required that the writer is *not* inserting and the [currentGroup] is empty.
   *
   * @return a list of the anchors that were moved.
   *
   *
   * [currentGroup]는 이동하지 않고, [table]의 [index] 위치 그룹을 [currentGroup] + [offset]의
   * 콘텐츠로 삽입합니다.
   *
   * writer는 삽입 중이 아니어야 하고, [currentGroup]은 비어 있어야 합니다.
   *
   * @return 이동된 앵커들의 목록을 반환합니다.
   */
  fun moveIntoGroupFrom(offset: Int, table: SlotTable, index: Int): List<Anchor> {
    runtimeCheck(insertCount <= 0 && groupSize(currentGroup + offset) == 1)

    val previousCurrentGroup = currentGroup
    val previousCurrentSlot = currentSlot
    val previousCurrentSlotEnd = currentSlotEnd

    advanceBy(amount = offset)
    startGroup()
    beginInsert()

    val anchors = table.write { writer ->
      moveGroup(
        fromWriter = writer,
        fromIndex = index,
        toWriter = this,
        updateFromCursor = false,
        updateToCursor = true,
      )
    }

    endInsert()
    endGroup()

    currentGroup = previousCurrentGroup
    currentSlot = previousCurrentSlot
    currentSlotEnd = previousCurrentSlotEnd

    return anchors
  }

  /**
   * Allocate an anchor to the current group or [index].
   *
   * 현재 그룹 또는 [index]에 대한 앵커를 할당합니다.
   */
  fun anchor(index: Int = currentGroup): Anchor =
    anchors.getOrAdd(index = index, effectiveSize = size) {
      Anchor(location = if (index <= groupGapStart) index else -(size - index))
    }

  fun markGroup(group: Int = parent) {
    val groupAddress = groupIndexToAddress(index = group)

    if (!groups.hasMark(address = groupAddress)) {
      groups.updateMark(address = groupAddress, value = true)

      if (!groups.containsMark(address = groupAddress)) {
        // This is a new mark, record the parent needs to update its contains mark.
        // 새로운 mark입니다. 부모가 contains mark를 갱신하도록 기록합니다.
        updateContainsMark(group = parent(index = group))
      }
    }
  }

  private fun containsGroupMark(group: Int): Boolean =
    group >= 0 && groups.containsMark(address = groupIndexToAddress(index = group))

  private fun containsAnyGroupMarks(group: Int): Boolean =
    group >= 0 && groups.containsAnyMark(address = groupIndexToAddress(index = group))

  private var pendingRecalculateMarks: PrioritySet? = null

  private fun recalculateMarks() {
    pendingRecalculateMarks?.let { marks ->
      while (marks.isNotEmpty()) {
        updateContainsMarkNow(group = marks.takeMax(), set = marks)
      }
    }
  }

  private fun updateContainsMark(group: Int) {
    if (group >= 0) {
      (pendingRecalculateMarks ?: PrioritySet().also { pendingRecalculateMarks = it })
        .add(value = group)
    }
  }

  // group의 자식 중에 마크가 있다면, group 자체에도 마크가 있다고 업데이트함
  private fun updateContainsMarkNow(group: Int, set: PrioritySet) {
    val groupAddress = groupIndexToAddress(index = group)
    val containsAnyMarks = childContainsAnyMarks(group = group)
    val markChanges = groups.containsMark(address = groupAddress) != containsAnyMarks

    if (markChanges) {
      groups.updateContainsMark(address = groupAddress, value = containsAnyMarks)

      val parent = parent(index = group)
      if (parent >= 0) set.add(parent)
    }
  }

  private fun childContainsAnyMarks(group: Int): Boolean {
    var child = group + 1
    val end = group + groupSize(index = group)

    while (child < end) {
      if (groups.containsAnyMark(address = groupIndexToAddress(index = child)))
        return true

      child += groupSize(index = child)
    }

    return false
  }

  /**
   * Return the current anchor location while changing the slot table.
   *
   * 슬롯 테이블을 변경하는 동안 현재 앵커 위치를 반환합니다.
   */
  fun anchorIndex(anchor: Anchor): Int =
    anchor.location.let { loc -> if (loc < 0) size + loc else loc }

  override fun toString(): String =
    "SlotWriter(" +
      "current=$currentGroup, " +
      "end=$currentGroupEnd, " +
      "size=$size, " +
      "gap=$groupGapStart-${groupGapStart + groupGapLen}" +
      ")"

  /**
   * Save [currentGroupEnd] to [endStack].
   *
   * [currentGroupEnd]를 [endStack]에 저장합니다.
   */
  private fun saveCurrentGroupEnd() {
    // Record the end location as relative to the end of the slot table so when we pop it
    // back off again all inserts and removes that happened while a child group was open
    // are already reflected into its value.
    //
    // 끝 위치를 슬롯 테이블의 끝을 기준으로 기록합니다. 이렇게 하면 자식 그룹이 열려 있는
    // 동안 발생한 모든 삽입과 제거가, 다시 pop될 때 이미 그 값에 반영되어 있습니다.
    endStack.push(size - currentGroupEnd)
  }

  /**
   * Restore [currentGroupEnd] from [endStack].
   *
   * [endStack]에서 [currentGroupEnd]를 복원합니다.
   */
  private fun restoreCurrentGroupEnd(): Int {
    val newGroupEnd = size - endStack.pop()
    currentGroupEnd = newGroupEnd
    return newGroupEnd
  }

  /**
   * As groups move their parent anchors need to be updated. This recursively updates the parent
   * anchors [parent] starting at [firstChild] and ending at [endGroup]. These are passed as a
   * parameter to as the [groups] does not contain the current values for [parent] yet.
   *
   * 그룹이 이동하면 부모 앵커를 갱신해야 합니다. [firstChild]에서 시작해 [endGroup]까지 부모 앵커
   * [parent]를 재귀적으로 갱신합니다. [groups]에는 아직 [parent]의 최신 값이 없으므로 이를 매개변수로
   * 전달합니다.
   */
  private fun fixParentAnchorsFor(parent: Int, endGroup: Int, firstChild: Int) {
    val parentAnchor = parentIndexToAnchor(index = parent, gapStart = groupGapStart)
    var child = firstChild

    while (child < endGroup) {
      groups.updateParentAnchor(
        address = groupIndexToAddress(index = child),
        value = parentAnchor,
      )

      val childEnd = child + groups.groupSize(address = groupIndexToAddress(index = child))

      fixParentAnchorsFor(
        parent = child,
        endGroup = childEnd,
        firstChild = child + 1,
      )

      child = childEnd
    }
  }

  /**
   * Move the gap in [groups] to [index].
   *
   * [groups]에서 gap을 [index]로 이동합니다.
   */
  private fun moveGroupGapTo(index: Int) {
    val gapLen = groupGapLen
    val gapStart = groupGapStart

    if (gapStart != index) {
      if (anchors.isNotEmpty())
        updateAnchors(previousGapStart = gapStart, newGapStart = index)

      if (gapLen > 0) {
        val groups = groups

        // Here physical is used to mean an index of the actual first int of the group in
        // the array as opposed to the logical address which is in groups of Group_Field_Size
        // integers. IntArray.copyInto expects physical indexes.
        //
        // opposed to: ~에 반대하는
        //
        // 여기서 physical은 그룹의 실제 첫 번째 정수가 배열에서 차지하는 인덱스를 의미합니다.
        // 이는 Group_Field_Size 단위로 묶인 논리적 주소와는 다릅니다. IntArray.copyInto는
        // 물리적(physical) 인덱스를 기대합니다.
        val groupPhysicalAddress = index * Group_Fields_Size
        val groupPhysicalGapLen = gapLen * Group_Fields_Size
        val groupPhysicalGapStart = gapStart * Group_Fields_Size

        // index 위치에 이미 슬롯이 있음 -> 슬롯을 위로 올려야 함
        if (index < gapStart) {
          groups.copyInto(
            destination = groups,
            destinationOffset = groupPhysicalAddress + groupPhysicalGapLen,
            startIndex = groupPhysicalAddress,
            endIndex = groupPhysicalGapStart,
          )
        }

        // index > gapStart
        // index 위치에 gap이 없음(이미 슬롯이 있음) -> 슬롯을 아래로 내려야 함
        else {
          groups.copyInto(
            destination = groups,
            destinationOffset = groupPhysicalGapStart,
            startIndex = groupPhysicalGapStart + groupPhysicalGapLen,
            endIndex = groupPhysicalAddress + groupPhysicalGapLen,
          )
        }
      }

      // Gap has moved so the anchor for the groups that moved have changed so the parent
      // anchors that refer to these groups must be updated.
      //
      // gap이 이동했으므로 이동된 그룹들의 앵커가 변경되었고, 따라서 이 그룹들을 참조하는
      // 부모 앵커들도 갱신해야 합니다.
      var groupAddress = if (index < gapStart) index + gapLen else gapStart
      val capacity = capacity

      runtimeCheck(groupAddress < capacity)

      while (groupAddress < capacity) {
        val oldAnchor = groups.parentAnchor(address = groupAddress)
        val oldIndex = parentAnchorToIndex(index = oldAnchor)
        val newAnchor = parentIndexToAnchor(index = oldIndex, gapStart = index)

        if (newAnchor != oldAnchor) {
          groups.updateParentAnchor(address = groupAddress, value = newAnchor)
        }

        groupAddress++

        if (groupAddress == index) groupAddress += gapLen
      }
    }

    this.groupGapStart = index
  }

  /**
   * Move the gap in [slots] to [index] where [group] is expected to receive any new slots added.
   *
   * [slots]에서 gap을 [index]로 옮겨, [group]이 추가되는 새 슬롯을 받을 수 있도록 합니다.
   */
  private fun moveSlotGapTo(index: Int, group: Int) {
    val gapLen = slotsGapLen
    val gapStart = slotsGapStart
    val slotsGapOwner = slotsGapOwner

    if (gapStart != index) {
      val slots = slots

      if (index < gapStart) {
        // move the gap down to index by shifting the data up.
        // 데이터를 위로 이동시켜 gap을 인덱스까지 아래로 내립니다.
        //
        // gap을 밑의 index까지 내리고, 기존 index 위치에 있던 데이터를 위로 올림
        slots.fastCopyInto(
          destination = slots,
          destinationOffset = index + gapLen,
          startIndex = index,
          endIndex = gapStart,
        )
      }

      // index > gapStart
      else {
        // Shift the data down, leaving the gap at index.
        // 데이터를 아래로 이동시켜 gap을 인덱스에 남겨 둡니다.
        //
        // gap을 위의 index까지 올리고, 기존 index 위치에 있던 데이터를 아래로 내림
        slots.fastCopyInto(
          destination = slots,
          destinationOffset = gapStart, // 빈 공간으로 데이터를 내려야 함
          startIndex = gapStart + gapLen, // gap 바로 뒤에 있던 데이터부터
          endIndex = index + gapLen, // 기존 index 위치에 있던 데이터들을 옮김
        )
      }
    }

    // Update the data anchors affected by the move.
    // 이동의 영향을 받는 데이터 앵커를 갱신합니다.
    val newSlotsGapOwner = min(group + 1, size)

    if (slotsGapOwner != newSlotsGapOwner) {
      val slotsSize = slots.size - gapLen

      if (newSlotsGapOwner < slotsGapOwner) {
        var updateAddress = groupIndexToAddress(index = newSlotsGapOwner)
        val stopUpdateAddress = groupIndexToAddress(index = slotsGapOwner)
        val groupGapStart = groupGapStart

        while (updateAddress < stopUpdateAddress) {
          val anchor = groups.dataAnchor(address = updateAddress)

          runtimeCheck(anchor >= 0) {
            // 예상치 못한 앵커 값입니다. 양수 앵커가 예상되었습니다.
            "Unexpected anchor value, expected a positive anchor"
          }

          groups.updateDataAnchor(
            address = updateAddress,
            anchor = -(slotsSize - anchor + 1),
          )

          updateAddress++

          if (updateAddress == groupGapStart)
            updateAddress += groupGapLen
        }
      }

      // newSlotsGapOwner > slotsGapOwner
      else {
        var updateAddress = groupIndexToAddress(index = slotsGapOwner)
        val stopUpdateAddress = groupIndexToAddress(index = newSlotsGapOwner)

        while (updateAddress < stopUpdateAddress) {
          val anchor = groups.dataAnchor(address = updateAddress)

          runtimeCheck(anchor < 0) {
            // 예상치 못한 앵커 값입니다. 음수 앵커가 예상되었습니다.
            "Unexpected anchor value, expected a negative anchor"
          }

          groups.updateDataAnchor(
            address = updateAddress,
            anchor = slotsSize + anchor + 1,
          )

          updateAddress++

          if (updateAddress == groupGapStart)
            updateAddress += groupGapLen
        }
      }

      this.slotsGapOwner = newSlotsGapOwner
    }

    this.slotsGapStart = index
  }

  private fun clearSlotGap() {
    val slotsGapStart = slotsGapStart
    val slotsGapEnd = slotsGapStart + slotsGapLen

    slots.fill(
      element = null,
      fromIndex = slotsGapStart,
      toIndex = slotsGapEnd,
    )
  }

  /**
   * Insert [size] number of groups in front of [currentGroup]. These groups are implicitly a
   * child of [parent].
   *
   * [currentGroup] 앞에 [size] 개의 그룹을 삽입합니다. 이 그룹들은 암시적으로 [parent]의
   * 자식이 됩니다.
   */
  private fun insertGroups(size: Int) {
    if (size > 0) {
      val currentGroup = currentGroup

      moveGroupGapTo(index = currentGroup)

      val gapStart = groupGapStart
      var gapLen = groupGapLen
      val oldCapacity = capacity
      val oldSize = this.size

      if (gapLen < size) {
        // Create a bigger gap.
        // 더 큰 gap을 생성합니다.
        val groups = groups

        // Double the size of the array, but at least MinGrowthSize and >= size.
        // 배열 크기를 두 배로 늘리되, 최소한 MinGrowthSize 이상이고 size보다 크거나 같아야 합니다.
        val newCapacity = max(max(oldCapacity * 2, oldSize + size), MinGroupGrowthSize)
        val newGroups = IntArray(newCapacity * Group_Fields_Size)
        val newGapLen = newCapacity - oldSize

        val oldGapEndAddress = gapStart + gapLen
        val newGapEndAddress = gapStart + newGapLen

        // Copy the old arrays into the new arrays.
        // 이전 배열들을 새 배열에 복사합니다.
        groups.copyInto(
          destination = newGroups,
          destinationOffset = 0,
          startIndex = 0,
          endIndex = gapStart * Group_Fields_Size,
        )
        groups.copyInto(
          destination = newGroups,
          destinationOffset = newGapEndAddress * Group_Fields_Size,
          startIndex = oldGapEndAddress * Group_Fields_Size,
          endIndex = oldCapacity * Group_Fields_Size,
        )

        // Update the gap and slots.
        // gap과 슬롯을 갱신합니다.
        this.groups = newGroups
        gapLen = newGapLen
      }

      // Move the currentGroupEnd to account for inserted groups.
      // 삽입된 그룹을 반영하기 위해 currentGroupEnd를 이동합니다.
      val currentEnd = currentGroupEnd
      if (currentEnd >= gapStart)
        this.currentGroupEnd = currentEnd + size

      // Update the gap start and length.
      // gap 시작 위치와 길이를 갱신합니다.
      this.groupGapStart = gapStart + size
      this.groupGapLen = gapLen - size

      // Replicate the current group data index to the new slots.
      // 현재 그룹 데이터 인덱스를 새 슬롯에 복제합니다.
      val index = if (oldSize > 0) dataIndex(index = currentGroup + size) else 0

      // If the slotGapOwner is before the current location ensure we get end relative offsets.
      // slotGapOwner가 현재 위치보다 앞에 있으면 끝 기준 오프셋을 얻도록 합니다.
      val anchor =
        dataIndexToDataAnchor(
          index = index,
          gapStart = if (slotsGapOwner < gapStart) 0 else slotsGapStart,
          gapLen = slotsGapLen,
          capacity = slots.size,
        )

      for (groupAddress in gapStart until gapStart + size) {
        groups.updateDataAnchor(address = groupAddress, anchor = anchor)
      }

      val slotsGapOwner = slotsGapOwner
      if (slotsGapOwner >= gapStart) {
        this.slotsGapOwner = slotsGapOwner + size
      }
    }
  }

  /**
   * Insert room into the slot table. This is performed by first moving the gap to [currentSlot]
   * and then reducing the gap [size] slots. If the gap is smaller than [size] the gap is grown to
   * at least accommodate [size] slots. The new slots are associated with [group].
   *
   * 슬롯 테이블에 공간을 삽입합니다. 이를 위해 먼저 gap을 [currentSlot]으로 옮긴 뒤, gap에서 [size]
   * 개수만큼 슬롯을 줄입니다. gap이 [size]보다 작으면 최소한 [size] 슬롯을 수용할 수 있도록 gap을
   * 확장합니다. 새 슬롯들은 [group]에 연결됩니다.
   */
  // MEMO 기존 gap을 slot으로 사용하거나, 새로운 gap을 추가함과 동시에 slot으로 사용하는 로직
  private fun insertSlots(size: Int, group: Int) {
    if (size > 0) {
      moveSlotGapTo(index = currentSlot, group = group)

      val gapStart = slotsGapStart
      var gapLen = slotsGapLen

      if (gapLen < size) {
        val slots = slots

        // Create a bigger gap.
        // 더 큰 gap을 생성합니다.
        val oldCapacity = slots.size
        val oldSize = oldCapacity - gapLen

        // Double the size of the array, but at least MinGrowthSize and >= size.
        // 배열 크기를 두 배로 늘리되, 최소한 MinGrowthSize 이상이고 size보다 크거나 같게 합니다.
        val newCapacity = max(max(oldCapacity * 2, oldSize + size), MinSlotsGrowthSize)
        val newData = Array<Any?>(newCapacity) { null }

        val newGapLen = newCapacity - oldSize
        val oldGapEndAddress = gapStart + gapLen
        val newGapEndAddress = gapStart + newGapLen

        // Copy the old arrays into the new arrays.
        // 이전 배열들을 새 배열에 복사합니다.
        slots.fastCopyInto(
          destination = newData,
          destinationOffset = 0,
          startIndex = 0,
          endIndex = gapStart,
        )
        slots.fastCopyInto(
          destination = newData,
          destinationOffset = newGapEndAddress,
          startIndex = oldGapEndAddress,
          endIndex = oldCapacity,
        )

        // Update the gap and slots.
        // gap과 슬롯을 갱신합니다.
        this.slots = newData
        gapLen = newGapLen
      }

      val currentDataEnd = currentSlotEnd
      if (currentDataEnd >= gapStart)
        this.currentSlotEnd = currentDataEnd + size

      // size 만큼 슬롯이 삽입되었으므로, gap 범위 오프셋을 조정함
      this.slotsGapStart = gapStart + size
      this.slotsGapLen = gapLen - size
    }
  }

  /**
   * Remove [len] group from [start].
   *
   * [start]부터 [len]개의 그룹을 제거합니다.
   */
  private fun removeGroups(start: Int, len: Int): Boolean =
    if (len > 0) {
      var anchorsRemoved = false
      val anchors = anchors

      // Move the gap to start of the removal and grow the gap.
      // gap을 제거 시작 위치로 옮기고 gap을 늘립니다.
      moveGroupGapTo(index = start)

      if (anchors.isNotEmpty()) {
        anchorsRemoved = removeAnchors(
          gapStart = start,
          size = len,
          sourceInformationMap = sourceInformationMap,
        )
      }

      groupGapStart = start

      val previousGapLen = groupGapLen
      val newGapLen = previousGapLen + len

      groupGapLen = newGapLen

      // Adjust the gap owner if necessary.
      // 필요하다면 gap 소유자를 조정합니다.
      val slotsGapOwner = slotsGapOwner
      if (slotsGapOwner > start) {
        // Use max here as if we delete the current owner this group becomes the owner.
        // 현재 소유자를 삭제하는 경우 이 그룹이 새 소유자가 되므로, 여기서는 max를 사용합니다.
        this.slotsGapOwner = max(start, slotsGapOwner - len)
      }

      if (currentGroupEnd >= groupGapStart)
        currentGroupEnd -= len

      val parent = parent

      // Update markers if necessary.
      // 필요하다면 마커를 갱신합니다.
      if (containsGroupMark(group = parent)) {
        updateContainsMark(group = parent)
      }

      // Remove the group from its parent source information.
      // 그룹을 부모 소스 정보에서 제거합니다.
      anchorsRemoved
    } else {
      false
    }

  internal fun sourceInformationOf(group: Int): GroupSourceInformation? =
    sourceInformationMap?.let { informationMap ->
      tryAnchor(group = group)?.let { anchor -> informationMap[anchor] }
    }

  internal fun tryAnchor(group: Int): Anchor? =
    if (group in 0 until size)
      anchors.find(index = group, effectiveSize = size)
    else
      null

  /**
   * Remove [len] slots from [start].
   *
   * [start] 위치에서 [len]개의 슬롯을 제거합니다.
   * (즉, len개 만큼 gap을 넣음)
   */
  private fun removeSlots(start: Int, len: Int, group: Int) {
    if (len > 0) {
      val gapLen = slotsGapLen
      val removeEnd = start + len

      // 기존에 있던 gap을 최상위로 올림 (겹치는 gap 없게!)
      moveSlotGapTo(index = removeEnd, group = group)

      slotsGapStart = start
      slotsGapLen = gapLen + len
      slots.fill(
        element = null,
        fromIndex = start,
        toIndex = start + len,
      )

      val currentDataEnd = currentSlotEnd
      if (currentDataEnd >= start)
        this.currentSlotEnd = currentDataEnd - len
    }
  }

  /**
   * A helper function to update the number of nodes in a group.
   *
   * 그룹의 노드 수를 갱신하는 보조 함수입니다.
   */
  private fun updateNodeOfGroup(index: Int, value: Any?) {
    val address = groupIndexToAddress(index = index)

    runtimeCheck(address < groups.size && groups.isNode(address = address)) {
      // $index 위치의 그룹이 노드 그룹으로 생성되지 않았는데 노드를 갱신하려고 했습니다.
      "Updating the node of a group at $index that was not created with as a node group"
    }

    slots[dataIndexToDataAddress(dataIndex = groups.nodeIndex(address = address))] = value
  }

  /**
   * A helper function to update the anchors as the gap in [groups] moves.
   *
   * [groups]의 gap이 이동할 때 앵커를 갱신하는 보조 함수입니다.
   */
  private fun updateAnchors(previousGapStart: Int, newGapStart: Int) {
    val size = size

    if (previousGapStart < newGapStart) {
      // Gap is moving up. All anchors between the new gap and the old gap switch to
      // be anchored to the front of the table instead of the end.
      //
      // gap이 위쪽으로 이동합니다. 새 gap과 기존 gap 사이의 모든 앵커는 끝이 아닌 테이블
      // 앞을 기준으로 하도록 전환합니다.
      var index = anchors.anchorLocationOf(index = previousGapStart, effectiveSize = size)

      while (index < anchors.size) {
        val anchor = anchors[index]
        val location = anchor.location
        if (location < 0) {
          val newLocation = size + location
          if (newLocation < newGapStart) {
            anchor.location = newLocation
            index++
          } else {
            break
          }
        } else {
          break
        }
      }
    }

    // previousGapStart >= newGapStart
    else {
      // Gap is moving down. All anchors between newGapStart and previousGapStart need
      // now to be anchored to the end of the table instead of the front of the table.
      //
      // gap이 아래로 이동합니다. newGapStart와 previousGapStart 사이의 모든 앵커는
      // 테이블 앞이 아닌 끝을 기준으로 하도록 전환합니다.
      var index = anchors.anchorLocationOf(index = newGapStart, effectiveSize = size)

      while (index < anchors.size) {
        val anchor = anchors[index]
        val location = anchor.location
        if (location >= 0) {
          anchor.location = -(size - location)
          index++
        } else break
      }
    }
  }

  /**
   * A helper function to remove the anchors for groups that are removed.
   *
   * 삭제된 그룹의 앵커를 제거하는 보조 함수입니다.
   */
  private fun removeAnchors(
    gapStart: Int,
    size: Int,
    sourceInformationMap: HashMap<Anchor, GroupSourceInformation>?,
  ): Boolean {
    val removeEnd = gapStart + size
    val groupsSize = this.size
    var index =
      anchors.anchorLocationOf(index = gapStart + size, effectiveSize = groupsSize).let { loc ->
        if (loc >= anchors.size) loc - 1 else loc
      }
    var removeAnchorEnd = 0
    var removeAnchorStart = index + 1

    while (index >= 0) {
      val anchor = anchors[index]
      val location = anchorIndex(anchor = anchor)

      if (location >= gapStart) {
        // location in gapStart..removeEnd
        if (location < removeEnd) {
          anchor.location = Int.MIN_VALUE
          sourceInformationMap?.remove(anchor)
          removeAnchorStart = index

          if (removeAnchorEnd == 0)
            removeAnchorEnd = index + 1
        }
        index--
      } else break
    }

    return (removeAnchorStart < removeAnchorEnd).also {
      if (it) anchors.subList(removeAnchorStart, removeAnchorEnd).clear()
    }
  }

  /**
   * A helper function to update anchors for groups that have moved.
   *
   * 이동된 그룹들의 앵커를 갱신하는 보조 함수입니다.
   */
  private fun moveAnchors(originalLocation: Int, newLocation: Int, size: Int) {
    val end = originalLocation + size
    val groupsSize = this.size

    // Remove all the anchors in range from the original location.
    // 원래 위치 범위에 있는 모든 앵커를 제거합니다.
    val index = anchors.anchorLocationOf(index = originalLocation, effectiveSize = groupsSize)
    val removedAnchors = mutableListOf<Anchor>()

    if (index >= 0) {
      while (index < anchors.size) {
        val anchor = anchors[index]
        val location = anchorIndex(anchor = anchor)

        @Suppress("ConvertTwoComparisonsToRangeCheck")
        if (location >= originalLocation && location < end) {
          removedAnchors.add(anchor)
          anchors.removeAt(index)
        } else break
      }
    }

    // Insert the anchors into there new location.
    // 앵커들을 새로운 위치에 삽입합니다.
    val moveDelta = newLocation - originalLocation
    removedAnchors.fastForEach { anchor ->
      val anchorIndex = anchorIndex(anchor = anchor)
      val newAnchorIndex = anchorIndex + moveDelta

      if (newAnchorIndex >= groupGapStart) {
        anchor.location = -(groupsSize - newAnchorIndex)
      } else {
        anchor.location = newAnchorIndex
      }

      val insertIndex = anchors.anchorLocationOf(index = newAnchorIndex, effectiveSize = groupsSize)
      anchors.add(/* index = */ insertIndex, /* element = */ anchor)
    }
  }

  /**
   * A debugging aid that emits [groups] as a string.
   *
   * [groups]를 문자열로 출력하는 디버깅 보조 도구입니다.
   */
  @Suppress("unused")
  fun toDebugString(): String =
    buildString {
      appendLine(this@SlotWriter.toString())

      appendLine("  parent   : $parent")
      appendLine("  current  : $currentGroup")
      appendLine("  group gap: $groupGapStart-${groupGapStart + groupGapLen}($groupGapLen)")
      appendLine("  slots gap: $slotsGapStart-${slotsGapStart + slotsGapLen}($slotsGapLen)")
      appendLine("  gap owner: $slotsGapOwner")

      for (index in 0 until size) {
        groupAsString(index = index)
        append('\n')
      }
    }

  /**
   * A debugging aid that emits a group as a string into a string builder.
   *
   * 그룹을 문자열로 [StringBuilder]에 출력하는 디버깅 보조 도구입니다.
   */
  private fun StringBuilder.groupAsString(index: Int) {
    val address = groupIndexToAddress(index = index)

    append("Group(")

    if (index < 10) append(' ')
    if (index < 100) append(' ')
    if (index < 1000) append(' ')
    append(index)

    if (address != index) {
      append("(")
      append(address)
      append(")")
    }

    append('#')
    append(groups.groupSize(address = address))
    append('^')
    append(parentAnchorToIndex(index = groups.parentAnchor(address = address)))
    append(": key=")
    append(groups.key(address = address))
    append(", nodes=")
    append(groups.nodeCount(address = address))
    append(", dataAnchor=")
    append(groups.dataAnchor(address = address))
    append(", parentAnchor=")
    append(groups.parentAnchor(address = address))

    if (groups.isNode(address = address)) {
      append(
        ", node=${
          slots[dataIndexToDataAddress(dataIndex = groups.nodeIndex(address = address))]
            .toString()
            .summarize(10)
        }",
      )
    }

    val startData = groups.slotIndex(address = address)
    val successorAddress = groupIndexToAddress(index = index + 1)
    val endData = groups.dataIndex(address = successorAddress)

    if (endData > startData) {
      append(", [")
      for (dataIndex in startData until endData) {
        if (dataIndex != startData) append(", ")
        val dataAddress = dataIndexToDataAddress(dataIndex = dataIndex)
        append(slots[dataAddress].toString().summarize(10))
      }
      append(']')
    }

    append(")")
  }

  internal fun verifyDataAnchors() {
    var previousDataIndex = 0
    val owner = slotsGapOwner
    var ownerFound = false
    val slotsSize = slots.size - slotsGapLen

    for (index in 0 until size) {
      val address = groupIndexToAddress(index = index)
      val dataAnchor = groups.dataAnchor(address = address)
      val dataIndex = groups.dataIndex(address = address)

      checkPrecondition(dataIndex >= previousDataIndex) {
        // 데이터 인덱스가 순서에서 벗어났습니다.
        // 위치: $index, 이전 = $previousDataIndex, 현재 = $dataIndex
        "Data index out of order at $index, previous = $previousDataIndex, current = $dataIndex"
      }
      checkPrecondition(dataIndex <= slotsSize) {
        // 데이터 인덱스 $dataIndex 가 $index 위치에서 범위를 벗어났습니다.
        "Data index, $dataIndex, out of bound at $index"
      }

      if (dataAnchor < 0 && !ownerFound) {
        checkPrecondition(owner == index) {
          // 슬롯 gap 소유자는 $owner 이어야 하는데, $index 에서 gap이 발견되었습니다.
          "Expected the slot gap owner to be $owner found gap at $index"
        }

        ownerFound = true
      }

      previousDataIndex = dataIndex
    }
  }

  @Suppress("unused")
  internal fun verifyParentAnchors() {
    val gapStart = groupGapStart
    val gapLen = groupGapLen
    val capacity = capacity

    for (groupAddress in 0 until gapStart) {
      val parentAnchor = groups.parentAnchor(address = groupAddress)

      checkPrecondition(parentAnchor > ParentAnchorPivot) {
        // $groupAddress 위치에서 시작 기준 앵커가 있어야 합니다.
        "Expected a start relative anchor at $groupAddress"
      }
    }

    for (groupAddress in gapStart + gapLen until capacity) {
      val parentAnchor = groups.parentAnchor(address = groupAddress)
      val parentIndex = parentAnchorToIndex(index = parentAnchor)

      if (parentIndex < gapStart) {
        checkPrecondition(parentAnchor > ParentAnchorPivot) {
          // $groupAddress 위치에서 시작 기준 앵커가 있어야 합니다.
          "Expected a start relative anchor at $groupAddress"
        }
      } else {
        checkPrecondition(parentAnchor <= ParentAnchorPivot) {
          // $groupAddress 위치에서 끝 기준 앵커가 있어야 합니다.
          "Expected an end relative anchor at $groupAddress"
        }
      }
    }
  }

  // 현재 그룹 배열이 최대로 가질 수 있는 그룹 수 (gap 크기 포함)
  private val capacity: Int
    get() = groups.size / Group_Fields_Size

  // 현재 그룹 배열 중 실제로 할당된 그룹의 수
  internal val size: Int
    get() = capacity - groupGapLen

  // MEMO index -> address 공식
  private fun groupIndexToAddress(index: Int): Int =
    // Branch-less of `if (index < groupGapStart) index else index + groupGapLen`.
    index + (groupGapLen * if (index < groupGapStart) 0 else 1)

  private fun dataIndexToDataAddress(dataIndex: Int): Int =
    // Branch-less of `if (dataIndex < slotsGapStart) dataIndex else dataIndex + slotsGapLen`.
    dataIndex + (slotsGapLen * if (dataIndex < slotsGapStart) 0 else 1)

  private fun IntArray.parent(index: Int): Int =
    parentAnchorToIndex(index = parentAnchor(address = groupIndexToAddress(index = index)))

  private fun dataIndex(index: Int): Int =
    groups.dataIndex(address = groupIndexToAddress(index = index))

  private fun IntArray.dataIndex(address: Int): Int =
    if (address >= capacity)
    // 사용 가능한 제일 가까운 슬롯 인덱스?
      slots.size - slotsGapLen
    else
      dataAnchorToDataIndex(
        anchor = dataAnchor(address = address),
        gapLen = slotsGapLen,
        capacity = slots.size,
      )

  private fun IntArray.slotIndex(address: Int): Int =
    if (address >= capacity)
    // 가장 먼저 비어 있는 슬롯 쓰는 듯?
      slots.size - slotsGapLen
    else
      dataAnchorToDataIndex(
        anchor = slotAnchor(address = address),
        gapLen = slotsGapLen,
        capacity = slots.size,
      )

  private fun IntArray.updateDataIndex(address: Int, dataIndex: Int) {
    updateDataAnchor(
      address = address,
      anchor = dataIndexToDataAnchor(
        index = dataIndex,
        gapStart = slotsGapStart,
        gapLen = slotsGapLen,
        capacity = slots.size,
      ),
    )
  }

  private fun IntArray.nodeIndex(address: Int): Int =
    dataIndex(address = address)

  // 않이!! 로직은 이해가 되는데, 이게 어떻게 성립하는지가 이해 안되니까 미치겠어..
  private fun IntArray.auxIndex(address: Int): Int =
    // STUDY aux 앞 비트들이 꺼져있는데(0), aux가 설정되어 있다면 이때의 index 계산은 괜찮나???
    dataIndex(address = address) + countOneBits(groupInfo(address = address) shr (Aux_Shift + 1))

  @Suppress("unused")
  private fun IntArray.dataIndexes(): List<Int> =
    groups.dataAnchors()
      .let { anchors ->
        anchors.slice(0 until groupGapStart) +
          anchors.slice(groupGapStart + groupGapLen until (size / Group_Fields_Size))
      }
      .fastMap { anchor ->
        dataAnchorToDataIndex(
          anchor = anchor,
          gapLen = slotsGapLen,
          capacity = slots.size,
        )
      }

  @Suppress("unused")
  private fun keys(): List<Int> =
    groups.keys().fastFilterIndexed { index, _ /* element */ ->
      index < groupGapStart || index >= groupGapStart + groupGapLen
    }

  private fun dataIndexToDataAnchor(index: Int, gapStart: Int, gapLen: Int, capacity: Int): Int =
    if (index > gapStart)
      -((capacity - gapLen) - index + 1)
    else
      index

  private fun dataAnchorToDataIndex(anchor: Int, gapLen: Int, capacity: Int): Int =
    if (anchor < 0)
      (capacity - gapLen) + anchor + 1
    else
      anchor

  // MEMO index -> anchor 공식
  private fun parentIndexToAnchor(index: Int, gapStart: Int): Int =
    if (index < gapStart) index else -(size - index - ParentAnchorPivot)

  private fun parentAnchorToIndex(index: Int): Int =
    if (index > ParentAnchorPivot) index else size + index - ParentAnchorPivot
}

// Summarize the toString of an object.
// 객체의 toString 결과를 요약합니다.
private fun String.summarize(minSize: Int) =
  this
    .replace("androidx.", "a.")
    .replace("compose.", "c.")
    .replace("runtime.", "r.")
    .replace("internal.", "ι.")
    .replace("ui.", "u.")
    .replace("Modifier", "μ")
    .replace("material.", "m.")
    .replace("Function", "λ")
    .replace("OpaqueKey", "κ")
    .replace("MutableState", "σ")
    .let { it.substring(0, min(minSize, it.length)) }

internal fun SlotTable.compositionGroupOf(group: Int): CompositionGroup =
  SlotTableGroup(
    table = this,
    address = group,
    version = version,
  )

private class SlotTableGroup(
  val table: SlotTable,
  val address: Int, // 원래 이름: group
  val version: Int = table.version,
) : CompositionGroup, Iterable<CompositionGroup> {
  override val isEmpty: Boolean
    get() = table.groups.groupSize(address = address) == 0

  override val key: Any
    get() =
      if (table.groups.hasObjectKey(address = address))
        table.slots[table.groups.objectKeyIndex(address = address)]!!
      else
        table.groups.key(address = address)

  override val sourceInfo: String?
    get() = table.sourceInformationOf(group = address)?.sourceInformation

  override val node: Any?
    get() =
      if (table.groups.isNode(address = address))
        table.slots[table.groups.nodeIndex(address = address)]!!
      else
        null

  override val data: Iterable<Any?>
    get() =
      table.sourceInformationOf(group = address)
        ?.let { sourceInformation ->
          SourceInformationGroupDataIterator(
            table = table,
            group = address,
            sourceInformation = sourceInformation,
          )
        }
        ?: DataIterator(table = table, group = address)

  override val identity: Any
    get() {
      validateRead()
      return table.read { reader -> reader.anchor(index = address) }
    }

  override val compositionGroups: Iterable<CompositionGroup>
    get() = this

  override fun iterator(): Iterator<CompositionGroup> {
    validateRead()
    return table.sourceInformationOf(group = address)
      ?.let { sourceInformation ->
        SourceInformationGroupIterator(
          table = table,
          parent = address,
          sourceInformation = sourceInformation,
          path = AnchoredGroupPath(group = address),
        )
      }
      ?: GroupIterator(
        table = table,
        start = address + 1,
        end = address + table.groups.groupSize(address = address),
      )
  }

  override val groupSize: Int
    get() = table.groups.groupSize(address = address)

  override val slotsSize: Int
    get() {
      val nextGroup = address + groupSize
      val nextSlot: Int =
        if (nextGroup < table.groupsSize)
          table.groups.dataAnchor(address = nextGroup)
        else
          table.slotsSize

      return nextSlot - table.groups.dataAnchor(address = address)
    }

  private fun validateRead() {
    if (table.version != version) {
      throwConcurrentModificationException()
    }
  }

  override fun find(identityToFind: Any): CompositionGroup? {
    fun findAnchoredGroup(anchor: Anchor): CompositionGroup? {
      if (table.ownsAnchor(anchor = anchor)) {
        val anchorGroup = table.anchorIndex(anchor = anchor)
        if (
          anchorGroup >= address &&
          anchorGroup - address < table.groups.groupSize(address = address)
        ) {
          return SlotTableGroup(
            table = table,
            address = anchorGroup,
            version = version,
          )
        }
      }

      return null
    }

    fun findRelativeGroup(group: CompositionGroup, index: Int): CompositionGroup? =
      group.compositionGroups.drop(index).firstOrNull()

    return when (identityToFind) {
      is Anchor -> findAnchoredGroup(anchor = identityToFind)
      is SourceInformationSlotTableGroupIdentity -> {
        find(identityToFind = identityToFind.parentIdentity)?.let { compositionGroup ->
          findRelativeGroup(group = compositionGroup, index = identityToFind.index)
        }
      }
      else -> null
    }
  }

  override fun equals(other: Any?): Boolean =
    other is SlotTableGroup &&
      other.address == address &&
      other.version == version &&
      other.table == table

  override fun hashCode(): Int = address + 31 * table.hashCode()
}

private data class SourceInformationSlotTableGroupIdentity(
  val parentIdentity: Any,
  val index: Int,
)

private sealed class SourceInformationGroupPath {
  abstract fun getIdentity(table: SlotTable): Any
}

private class AnchoredGroupPath(val group: Int) : SourceInformationGroupPath() {
  override fun getIdentity(table: SlotTable): Anchor =
    table.anchor(index = group)

  override fun equals(other: Any?): Boolean =
    other is AnchoredGroupPath && other.group == group

  override fun hashCode(): Int = group * 31
}

private class RelativeGroupPath(
  val parent: SourceInformationGroupPath,
  val index: Int,
) : SourceInformationGroupPath() {
  override fun getIdentity(table: SlotTable): SourceInformationSlotTableGroupIdentity =
    SourceInformationSlotTableGroupIdentity(
      parentIdentity = parent.getIdentity(table = table),
      index = index,
    )

  override fun equals(other: Any?): Boolean =
    other is RelativeGroupPath && other.parent == parent && other.index == index

  override fun hashCode(): Int = index * 31 + parent.hashCode()
}

private class SourceInformationSlotTableGroup(
  val table: SlotTable,
  val parent: Int,
  val sourceInformation: GroupSourceInformation,
  val identityPath: SourceInformationGroupPath,
) : CompositionGroup, Iterable<CompositionGroup> {
  override val key: Int = sourceInformation.key

  override val sourceInfo: String?
    get() = sourceInformation.sourceInformation

  override val node: Any?
    get() = null

  override val data: Iterable<Any?>
    get() = SourceInformationGroupDataIterator(
      table = table,
      group = parent,
      sourceInformation = sourceInformation,
    )

  override val compositionGroups: Iterable<CompositionGroup> = this

  override val identity: Any
    get() = identityPath.getIdentity(table = table)

  override val isEmpty: Boolean
    get() = sourceInformation.groups?.isEmpty() != false

  override fun iterator(): Iterator<CompositionGroup> =
    SourceInformationGroupIterator(
      table = table,
      parent = parent,
      sourceInformation = sourceInformation,
      path = identityPath,
    )

  override fun equals(other: Any?): Boolean =
    other is SourceInformationSlotTableGroup &&
      // sourceInformation is intentionally omitted from this list as its value is implied
      // by parent, table and identityPath. In other words, these form a key to the
      // sourceInformation and it will never compare unequal when the others are equal.
      //
      // sourceInformation은 의도적으로 이 목록에서 제외됩니다. 그 값은 parent, table,
      // identityPath에 의해 암시되며, 다시 말해 이들이 sourceInformation의 키를 형성하므로
      // 다른 값들이 동일할 때 sourceInformation만 다르게 비교되는 일은 없습니다.
      other.parent == parent &&
      other.table == table &&
      other.identityPath == identityPath

  override fun hashCode(): Int {
    var result = parent
    result += table.hashCode() * 31
    result += identityPath.hashCode() * 31
    return result
  }
}

private class GroupIterator(
  val table: SlotTable,
  start: Int,
  val end: Int,
) : Iterator<CompositionGroup> {
  private var index = start
  private val version = table.version

  init {
    if (table.writer) throwConcurrentModificationException()
  }

  override fun hasNext(): Boolean = index < end

  override fun next(): CompositionGroup {
    validateRead()

    val group = index

    index += table.groups.groupSize(address = group)

    return SlotTableGroup(table = table, address = group, version = version)
  }

  private fun validateRead() {
    if (table.version != version) {
      throwConcurrentModificationException()
    }
  }
}

private class DataIterator(val table: SlotTable, group: Int) : Iterable<Any?>, Iterator<Any?> {
  val start: Int = table.groups.dataAnchor(address = group)
  val end: Int =
    if (group + 1 < table.groupsSize)
      table.groups.dataAnchor(address = group + 1)
    else
      table.slotsSize

  var index: Int = start

  override fun iterator(): Iterator<Any?> = this

  override fun hasNext(): Boolean = index < end

  override fun next(): Any? =
    (if (index >= 0 && index < table.slots.size)
      table.slots[index]
    else
      null)
      .also { index++ }
}

private class SourceInformationGroupDataIterator(
  val table: SlotTable,
  group: Int,
  sourceInformation: GroupSourceInformation,
) : Iterable<Any?>, Iterator<Any?> {
  private val base: Int = table.groups.dataAnchor(address = group)
  private val start: Int = sourceInformation.dataStartOffset
  private val end: Int =
    sourceInformation.dataEndOffset.let { endOffset ->
      if (endOffset > 0) endOffset
      else
        (if (group + 1 < table.groupsSize)
          table.groups.dataAnchor(address = group + 1)
        else
          table.slotsSize
          ) - base
    }

  private val filter =
    BitVector().also { vector ->
      // Filter any groups.
      // 모든 그룹을 걸러냅니다.
      val groups = sourceInformation.groups ?: return@also
      groups.fastForEach { info ->
        if (info is GroupSourceInformation) {
          vector.setRange(start = info.dataStartOffset, end = info.dataEndOffset)
        }
      }
    }
  private var index: Int = filter.nextClear(index = start)

  override fun iterator(): Iterator<Any?> = this

  override fun hasNext(): Boolean = index < end

  override fun next(): Any? =
    (if (index in 0 until end) table.slots[base + index] else null)
      .also { index = filter.nextClear(index = index + 1) }
}

private val EmptyLongArray = LongArray(size = 0)

internal class BitVector {
  private var first: Long = 0L
  private var second: Long = 0L
  private var others: LongArray = EmptyLongArray

  // 저장된 전체 비트 개수
  val size: Int
    // other는 0개일 수 있지만, first/second는 항상 존재하므로 +2를 함.
    // Long은 64개의 비트를 저장함.
    get() = (others.size + 2) * 64

  operator fun get(index: Int): Boolean {
    if (index < 64) return first and (1L shl index) != 0L
    if (index < 128) return second and (1L shl (index - 64)) != 0L


    // 128 이상의 인덱스는 others에서 관리

    val others = others
    val size = others.size
    if (size == 0) return false

    // 128 / 64 = 2 이므로, 2를 빼줌.
    val address = (index / 64) - 2
    if (address >= size) return false

    val bit = index % 64
    return (others[address] and (1L shl bit)) != 0L
  }

  operator fun set(index: Int, value: Boolean) {
    if (index < 64) {
      val mask = 1L shl index
      first = (first and mask.inv()) or (value.toBit().toLong() shl index)
      return
    }

    if (index < 128) {
      val mask = 1L shl (index - 64)
      second = (second and mask.inv()) or (value.toBit().toLong() shl index)
      return
    }

    val address = (index / 64) - 2
    val newIndex = index % 64
    val mask = 1L shl newIndex
    var others = others

    if (address >= others.size) {
      others = others.copyOf(newSize = address + 1)
      this.others = others
    }

    val bits = others[address]
    others[address] = (bits and mask.inv()) or (value.toBit().toLong() shl newIndex)
  }

  // index 부터(이상) 시작하여 먼저 나오는 1 비트의 위치를 반환함
  fun nextSet(index: Int): Int =
    nextBit(index = index, valueSelector = { it })

  // index 부터(이상) 시작하여 먼저 나오는 0 비트의 위치를 반환함
  fun nextClear(index: Int): Int =
    nextBit(index = index, valueSelector = { it.inv() })

  /**
   * Returns the index of the next bit in this bit vector, starting at index. The [valueSelector]
   * lets the caller modify the value before finding its first bit set.
   *
   * 이 비트 벡터에서 index부터 시작하여 다음 1 비트의 인덱스를 반환합니다. [valueSelector]를
   * 사용하면 호출자가 첫 번째로 1인 비트를 찾기 전에 값을 수정할 수 있습니다.
   */
  private inline fun nextBit(index: Int, valueSelector: (Long) -> Long): Int {
    if (index < 64) {
      // We shift right (unsigned) then back left to drop the first "index"
      // bits. This will set them all to 0, thus guaranteeing that the search
      // performed by [firstBitSet] will start at index.
      //
      // 오른쪽으로 부호 없는 시프트를 한 뒤 다시 왼쪽으로 시프트하여 처음
      // “index” 전 비트들을 제거합니다. 이렇게 하면 index 까지의 비트들이 모두
      // 0이 되어, [firstBitSet]의 탐색이 index에서 시작됨을 보장합니다.
      val bit = (valueSelector(first) ushr index shl index).firstBitSet
      if (bit < 64) return bit
    }

    if (index < 128) {
      val index = index - 64
      val bit = (valueSelector(second) ushr index shl index).firstBitSet
      if (bit < 64) return 64 + bit
    }

    val index = max(index, 128)
    val start = (index / 64) - 2
    val others = others

    for (i in start until others.size) {
      var value = valueSelector(others[i])

      // For the first element, the start index may be in the middle of the
      // 128 bit word, so we apply the same shift trick as for [first] and
      // [second] to start at the right spot in the bit field.
      //
      // 첫 번째 요소의 경우 시작 인덱스가 128비트 워드의 중간일 수 있으므로,
      // 비트 필드에서 올바른 위치에서 시작하기 위해 [first]와 [second]에서
      // 사용한 것과 동일한 시프트 기법을 적용합니다.
      if (i == start) {
        val shift = index % 64
        value = value ushr shift shl shift
      }

      val bit = value.firstBitSet
      if (bit < 64) return 128 + i * 64 + bit
    }

    return Int.MAX_VALUE
  }

  // start..end 범위의 비트를 1로 설정함
  fun setRange(start: Int, end: Int) {
    var start = start

    // If the range is valid we will use ~0L as our mask to create strings of 1s below,
    // otherwise we use 0 so we don't set any bits. We could return when start >= end
    // but this won't be a common case, so skip the branch.
    //
    // 범위가 유효하다면 마스크로 ~0L을 사용하여 아래에서 1로 채워진 비트를 만듭니다.
    // 그렇지 않다면 비트를 설정하지 않도록 0을 사용합니다. start가 end 이상일 때 바로
    // 반환할 수도 있지만, 이는 흔한 경우가 아니므로 분기 처리는 생략합니다.
    //
    // -1L은 모든 비트가 1인 Long임
    val bits = if (start < end) -1L else 0L

    // Set the bits to 0 if we don't need to set any bit in the first word.
    // 첫 번째 워드에서 비트를 설정할 필요가 없으면 해당 비트들을 0으로 설정합니다.
    //
    // start가 64보다 클 때만 selector는 0이 됨. 아니라면 항상 -1.
    var selector = bits * (start < 64).toBit()

    // Take our selector (either all 0s or all 1s), perform an unsigned shift to the
    // right to create a new word with "clampedEnd - start" bits, then shift it back
    // left to where the range begins. This lets us set up to 64 bits at a time without
    // doing an expensive loop that calls set().
    //
    // selector(모두 0이거나 모두 1)를 오른쪽으로 부호 없는 시프트하여 "clampedEnd - start"
    // 비트를 가진 새로운 워드를 만든 뒤, 범위가 시작되는 위치로 다시 왼쪽 시프트합니다.
    // 이렇게 하면 set()을 반복 호출하는 비싼 루프 없이 한 번에 최대 64비트를 설정할 수
    // 있습니다.
    //
    // MEMO 비트마스킹 마법 대박이당...
    //  https://github.com/jisungbin/JvmPlayground/blob/b4470d23945148cc5970a69afc084901d327a30c/src/main/kotlin/main.kt
    val firstValue = (
      // 필요한 만큼만 1 비트를 준비하고..
      selector ushr (
        // 실제로 설정해야 하는 비트까지의 시프팅 거리 계산
        64 -
          // 실제로 설정해야 하는 비트 수 계산
          (min(64, end) - start)
        )

      // 1 비트가 시작되어야 할 곳으로 시프팅
      ) shl start

    first = first or firstValue

    // If we need to set bits in the second word, clamp our start otherwise return now.
    //
    // 두 번째 워드에 비트를 설정해야 한다면 start 값을 제한(clamp)하고, 그렇지 않으면
    // 바로 반환합니다.
    if (end > 64) start = max(start, 64) else return

    // Set the bits to 0 if we don't need to set any bit in the second word.
    // 두 번째 워드에서 비트를 설정할 필요가 없으면 해당 비트들을 0으로 설정합니다.
    //
    // start가 128보다 클 때만 selector는 0이 됨. 아니라면 항상 -1.
    selector = bits * (start < 128).toBit()

    // See firstValue above.
    // 위의 firstValue를 참조하세요.
    val secondValue = (selector ushr (128 - (min(128, end) - start))) shl start

    second = second or secondValue

    // If we need to set bits in the remainder array, clamp our start otherwise return now.
    //
    // 나머지 배열에 비트를 설정해야 한다면 start 값을 제한(clamp)하고, 그렇지 않으면 바로
    // 반환합니다.
    if (end > 128) start = max(start, 128) else return

    for (bit in start until end) this[bit] = true
  }

  override fun toString(): String =
    buildString {
      var first = true
      append("BitVector [")
      for (i in 0 until size) {
        if (this@BitVector[i]) {
          if (!first) append(", ")
          first = false
          append(i)
        }
      }
      append(']')
    }
}

// 여기서 set은 자료구조 Set이 아니라 1로 설정된 비트를 의미함
private val Long.firstBitSet: Int
  inline get() = countTrailingZeroBits()

private class SourceInformationGroupIterator(
  val table: SlotTable,
  val parent: Int,
  val sourceInformation: GroupSourceInformation,
  val path: SourceInformationGroupPath,
) : Iterator<CompositionGroup> {
  private val version: Int = table.version
  private var index = 0

  override fun hasNext(): Boolean =
    sourceInformation.groups?.let { groups -> index < groups.size } ?: false

  override fun next(): CompositionGroup =
    when (val group = sourceInformation.groups?.get(index++)) {
      is Anchor -> {
        SlotTableGroup(
          table = table,
          address = group.location,
          version = version,
        )
      }

      is GroupSourceInformation -> {
        SourceInformationSlotTableGroup(
          table = table,
          parent = parent,
          sourceInformation = group,
          identityPath = RelativeGroupPath(parent = path, index = index - 1),
        )
      }

      else -> composeRuntimeError("Unexpected group information structure")
    }
}

// Parent -1 is reserved to be the root parent index so the anchor must pivot on -2.
// 부모가 -1인 값은 루트 부모 인덱스로 예약되어 있으므로, 앵커는 -2를 기준으로 해야 합니다.
//
// STUDY 이 상수 이해하기....
private const val ParentAnchorPivot = -2

// Group layout
//  0             | 1             | 2             | 3             | 4             |
//  Key           | Group info    | Parent anchor | Size          | Data anchor   |
private const val Key_Offset = 0
private const val GroupInfo_Offset = 1
private const val ParentAnchor_Offset = 2
private const val Size_Offset = 3
private const val DataAnchor_Offset = 4

// 하나의 그룹은 5개의 필드로 구성되어 있음 (하나의 그룹당 5개의 슬롯 사용?)
private const val Group_Fields_Size = 5


// Key is the key parameter passed into startGroup.
// Key는 startGroup에 전달되는 key 매개변수입니다.

// [Group info] is laid out as follows,
// [Group info]는 다음과 같이 배치됩니다:
//
// [31 30 29 28]_[27 26 25 24]_[23 22 21 20]_[19 18 17 16]__[15 14 13 12]_[11 10 09 08]_[07 06 05 04]_[03 02 01 00]
//  0  n  ks ds   m  cm |                             node count (0 ~ 25 bits)                                    |
//
// where n is set when the group represents a node.
// where ks is whether the group has a object key slot.
// where ds is whether the group has a group data slot.
// where m is whether the group is marked.
// where cm is whether the group contains a mark.
//
// (MSB는 항상 0인 듯!)
//
// n은 그룹이 노드를 나타낼 때 설정됩니다.
// ks는 그룹에 object key 슬롯이 있는지를 나타냅니다.
// ds는 그룹에 group data 슬롯이 있는지를 나타냅니다. (Aux에 해당)
// m은 그룹이 표시(marked)되었는지를 나타냅니다.
// cm은 그룹이 표시(mark)를 포함하는지를 나타냅니다.


// Masks and flags

// n은 그룹이 노드를 나타낼 때 설정됩니다.
private const val NodeBit_Mask = 0b0100_0000_0000_0000__0000_0000_0000_0000
private const val NodeBit_Shift = 30

// ks는 그룹에 object key 슬롯이 있는지를 나타냅니다.
private const val ObjectKey_Mask = 0b0010_0000_0000_0000__0000_0000_0000_0000
private const val ObjectKey_Shift = 29

// ds는 그룹에 group data 슬롯이 있는지를 나타냅니다.
private const val Aux_Mask = 0b0001_0000_0000_0000__0000_0000_0000_0000
private const val Aux_Shift = 28

// m은 그룹이 표시(marked)되었는지를 나타냅니다.
private const val Mark_Mask = 0b0000_1000_0000_0000__0000_0000_0000_0000
private const val Mark_Shift = 27

// cm은 그룹이 표시(mark)를 포함하는지를 나타냅니다.
private const val ContainsMark_Mask = 0b0000_0100_0000_0000__0000_0000_0000_0000
private const val ContainsMark_Shift = 26

private const val NodeCount_Mask = 0b0000_0011_1111_1111__1111_1111_1111_1111

// STUDY 상수명의 의미..
private const val Slots_Shift = Aux_Shift

// Parent anchor is a group anchor to the parent, as the group gap is moved this value is updated to
// refer to the parent.
//
// Parent anchor는 부모를 가리키는 그룹 앵커이며, 그룹 gap이 이동할 때 이 값은 부모를 참조하도록
// 갱신됩니다.

// Slot count is the total number of group slots, including itself, occupied by the group.
// 슬롯 수는 해당 그룹이 사용하는 그룹 슬롯의 총 개수(자신 포함)입니다.
// (occupied: 사용(되는) 중인, 점령된)

// Data anchor is an anchor to the group data. The value is positive if it is before the data gap
// and it is negative if it is after the data gap. As gaps are moved, these values are updated.
//
// Data anchor는 그룹 데이터를 가리키는 앵커입니다. 값이 데이터 gap 이전이면 양수이고,
// 이후이면 음수입니다. gap이 이동하면 이 값은 갱신됩니다.


// Special values

// The minimum number of groups to allocate the group table.
// 그룹 테이블을 할당하기 위한 최소 그룹 수입니다.
private const val MinGroupGrowthSize = 32

// The minimum number of data slots to allocate in the data slot table.
// 데이터 슬롯 테이블에 할당할 최소 데이터 슬롯 수입니다.
private const val MinSlotsGrowthSize = 32


// address: gap을 무시한 배열의 절대 오프셋을 말합니다.
private inline fun IntArray.groupInfo(address: Int): Int =
  this[address * Group_Fields_Size + GroupInfo_Offset]

private inline fun IntArray.isNode(address: Int): Boolean =
  groupInfo(address = address) and NodeBit_Mask != 0

private inline fun IntArray.nodeIndex(address: Int): Int =
  this[address * Group_Fields_Size + DataAnchor_Offset]

private inline fun IntArray.hasObjectKey(address: Int): Boolean =
  groupInfo(address = address) and ObjectKey_Mask != 0

private fun IntArray.objectKeyIndex(address: Int): Int =
  this[address * Group_Fields_Size + DataAnchor_Offset] +
    countOneBits(groupInfo(address = address) shr (ObjectKey_Shift + 1))

private inline fun IntArray.hasAux(address: Int): Boolean =
  groupInfo(address = address) and Aux_Mask != 0

private fun IntArray.addAux(address: Int) {
  val groupInfoIndex = address * Group_Fields_Size + GroupInfo_Offset
  this[groupInfoIndex] = this[groupInfoIndex] or Aux_Mask
}

private inline fun IntArray.hasMark(address: Int): Boolean =
  groupInfo(address = address) and Mark_Mask != 0

private fun IntArray.updateMark(address: Int, value: Boolean) {
  val groupInfoIndex = address * Group_Fields_Size + GroupInfo_Offset
  this[groupInfoIndex] =
    (this[groupInfoIndex] and Mark_Mask.inv()) or // inv(): Mark_Mask 위치에 있는 비트를 0으로 만듦
      (value.toBit() shl Mark_Shift)
}

private inline fun IntArray.containsMark(address: Int): Boolean =
  groupInfo(address = address) and ContainsMark_Mask != 0

private fun IntArray.updateContainsMark(address: Int, value: Boolean) {
  val groupInfoIndex = address * Group_Fields_Size + GroupInfo_Offset
  this[groupInfoIndex] =
    (this[groupInfoIndex] and ContainsMark_Mask.inv()) or (value.toBit() shl ContainsMark_Shift)
}

private inline fun IntArray.containsAnyMark(address: Int): Boolean =
  groupInfo(address = address) and (ContainsMark_Mask or Mark_Mask) != 0

private fun IntArray.auxIndex(address: Int): Int =
  (address * Group_Fields_Size).let { groupIndex ->
    if (groupIndex >= size) size
    else
      this[groupIndex + DataAnchor_Offset] +
        // STUDY Aux_Shift에 1을 더하는 이유: (ChatGPT 설명)
        //
        //    `+1`을 하는 이유는 “현재 비트는 제외하고, 그 앞(상위) 비트들만”의 개수를 세기 위해서예요.
        //
        //    - `groupInfo`에는 슬롯을 차지할 수 있는 플래그들이 상위 비트부터 순서대로 들어가 있어요:
        //      `n(30)`, `ks(29)`, `ds(28)` …
        //    - 어떤 슬롯의 인덱스를 계산할 때는, 해당 슬롯의 시작 오프셋(= data anchor)에 “그 슬롯보다
        //      앞에 올 수 있는 슬롯 개수”만 더해야 해요.
        //    - 그래서 `shr(Shift + 1)`로 현재 슬롯의 비트는 우측 시프트로 날려 버리고, 더 높은 비트들만
        //      남긴 뒤 `countOneBits(...)`로 그 개수만 합산합니다.
        //
        //    예시
        //      - `auxIndex`: `shr(Aux_Shift + 1)` → `ds(28)` 자신은 제외되고, 앞선 `ks(29)`, `n(30)`만
        //                                           집계 ⇒ aux 위치 = base + (n ? 1 : 0) + (ks ? 1 : 0).
        //      - `objectKeyIndex`: `shr(ObjectKey_Shift + 1)` → `ks(29)` 자신은 제외되고, 앞선 `n(30)`만
        //                                                       집계 ⇒ object key 위치 = base + (n ? 1 : 0).
        //
        //    만약 `+1`이 없다면 현재 비트까지 포함되어 오프셋이 1 크게 계산되는 오류가 납니다.
        //
        // 하지만 아직 이해 못함!
        countOneBits(groupInfo(address = address) shr (Aux_Shift + 1))
  }

private fun IntArray.slotAnchor(address: Int): Int =
  dataAnchor(address = address) +
    // shr 결과로 n, ks, ds 플래그만 남을 듯?
    //
    //   - n은 그룹이 노드를 나타낼 때 설정됩니다.
    //   - ks는 그룹에 object key 슬롯이 있는지를 나타냅니다.
    //   - ds는 그룹에 group data 슬롯이 있는지를 나타냅니다. (Aux에 해당)
    countOneBits(groupInfo(address = address) shr Slots_Shift)

private inline fun countOneBits(value: Int): Int = value.countOneBits()

// Key access
private inline fun IntArray.key(address: Int): Int =
  this[address * Group_Fields_Size + Key_Offset]

private fun IntArray.keys(length: Int = size): List<Int> =
  slice(Key_Offset until length step Group_Fields_Size)

// Node count access
private inline fun IntArray.nodeCount(address: Int): Int =
  groupInfo(address = address) and NodeCount_Mask

private fun IntArray.updateNodeCount(address: Int, value: Int) {
  @Suppress("ConvertTwoComparisonsToRangeCheck")
  debugRuntimeCheck(value >= 0 && value < NodeCount_Mask)

  this[address * Group_Fields_Size + GroupInfo_Offset] =
    (groupInfo(address = address) and NodeCount_Mask.inv()) or value
}

private fun IntArray.nodeCounts(length: Int = size): List<Int> =
  slice(GroupInfo_Offset until length step Group_Fields_Size)
    .fastMap { groupInfoSlot -> groupInfoSlot and NodeCount_Mask }

// Parent anchor
private inline fun IntArray.parentAnchor(address: Int): Int =
  this[address * Group_Fields_Size + ParentAnchor_Offset]

private inline fun IntArray.updateParentAnchor(address: Int, value: Int) {
  this[address * Group_Fields_Size + ParentAnchor_Offset] = value
}

private fun IntArray.parentAnchors(length: Int = size): List<Int> =
  slice(ParentAnchor_Offset until length step Group_Fields_Size)

// Slot count access
private fun IntArray.groupSize(address: Int): Int =
  this[address * Group_Fields_Size + Size_Offset]

private fun IntArray.updateGroupSize(address: Int, value: Int) {
  debugRuntimeCheck(value >= 0)
  this[address * Group_Fields_Size + Size_Offset] = value
}

private fun IntArray.slice(indices: Iterable<Int>): List<Int> {
  val list = mutableListOf<Int>()
  for (index in indices) {
    list.add(get(index))
  }
  return list
}

private fun IntArray.groupSizes(length: Int = size): List<Int> =
  slice(Size_Offset until length step Group_Fields_Size)

// Data anchor access
private inline fun IntArray.dataAnchor(address: Int): Int =
  this[address * Group_Fields_Size + DataAnchor_Offset]

private inline fun IntArray.updateDataAnchor(address: Int, anchor: Int) {
  this[address * Group_Fields_Size + DataAnchor_Offset] = anchor
}

private fun IntArray.dataAnchors(length: Int = size): List<Int> =
  slice(DataAnchor_Offset until length step Group_Fields_Size)

// Update data
private fun IntArray.initGroup(
  address: Int,
  key: Int,
  isNode: Boolean,
  hasObjectKey: Boolean, // 원래 이름: hasDataKey
  hasAux: Boolean, // 원래 이름: hasData
  parentAnchor: Int,
  dataAnchor: Int,
) {
  val groupIndex = address * Group_Fields_Size

  this[groupIndex + Key_Offset] = key

  // We turn each boolean into its corresponding bit field at the same time as we "or"
  // the fields together so we the generated aarch64 code can use left shifted operands
  // in the "orr" instructions directly.
  //
  // 각 boolean 값을 해당 비트 필드로 변환하면서 동시에 필드들을 OR 연산으로 합칩니다.
  // 이렇게 하면 생성된 aarch64 코드가 orr 명령어에서 왼쪽 시프트된 피연산자를 직접
  // 사용할 수 있습니다.
  this[groupIndex + GroupInfo_Offset] =
    (isNode.toBit() shl NodeBit_Shift) or
      (hasObjectKey.toBit() shl ObjectKey_Shift) or
      (hasAux.toBit() shl Aux_Shift)

  this[groupIndex + ParentAnchor_Offset] = parentAnchor
  this[groupIndex + Size_Offset] = 0
  this[groupIndex + DataAnchor_Offset] = dataAnchor
}

private inline fun Boolean.toBit(): Int = if (this) 1 else 0

private fun IntArray.updateGroupKey(address: Int, key: Int) {
  val groupIndex = address * Group_Fields_Size
  this[groupIndex + Key_Offset] = key
}

private inline fun ArrayList<Anchor>.getOrAdd(
  index: Int,
  effectiveSize: Int,
  newAnchor: () -> Anchor,
): Anchor {
  val location = searchAnchorLocation(location = index, effectiveSize = effectiveSize)
  return if (location < 0) {
    val anchor = newAnchor()
    add(abs(location + 1), anchor)
    anchor
  } else get(location)
}

private fun ArrayList<Anchor>.find(index: Int, effectiveSize: Int): Anchor? {
  val location = searchAnchorLocation(location = index, effectiveSize = effectiveSize)
  return if (location >= 0) get(location) else null
}

/**
 * This is inlined here instead to avoid allocating a lambda for the compare when
 * this is used.
 *
 * 이 코드는 사용될 때 비교를 위한 람다를 할당하지 않도록 여기서 인라인 처리합니다.
 */
// 아마도 location는 그룹 위치겠지?
//
// MEMO 이진 탐색 알고리즘!!!
//
// MEMO @param effectiveSize
//  > 앵커가 음수라면 배열 끝에서의 거리를 기록합니다.
//  음수의 앵커 인덱스를 양수로 바꿀 때 사용되는 전체 그룹 배열의 사이즈
//
// 원래 이름: search
private fun ArrayList<Anchor>.searchAnchorLocation(location: Int, effectiveSize: Int): Int {
  var lowIndex = 0
  var highIndex = lastIndex

  while (lowIndex <= highIndex) {
    // 'n / (2^k)' 는 'n shr k' 와 동일함. (n이 양수일 때만 해당, 수학적으로 원리는 아직 이해 못함!)
    // 즉, 'n / 2' 는 'n shr 1' 과 동일함.
    //
    // 'lowIndex + highIndex' 연산에서 overflow가 발생하면 덧셈 결과는 음수가 됨.
    // '음수 shr k' 로는 음수 값만 발생(shr는 MSB를 유지하며 shift 함)하므로, ushr로 'n / 2'를 진행함.
    // (ushr는 MSB를 0으로 채우며 shift 함)
    val midIndex = (lowIndex + highIndex) ushr 1 // safe from overflows
    val midValue = get(midIndex).location.let { if (it < 0) effectiveSize + it else it }

    @Suppress("KotlinConstantConditions")
    when {
      midValue < location -> lowIndex = midIndex + 1
      midValue == location -> return midIndex // key found
      midValue > location -> highIndex = midIndex - 1
    }
  }

  return -(lowIndex + 1) // key not found
}

/**
 * A wrapper on [searchAnchorLocation] that always returns an index in to [this] even if [index] is
 * not in the array list.
 *
 * [searchAnchorLocation]의 결과를 감싸는 래퍼로, [index]가 배열 리스트에 없더라도 항상 [this] 안의
 * 인덱스를 반환합니다.
 */
private fun ArrayList<Anchor>.anchorLocationOf(index: Int, effectiveSize: Int): Int =
  searchAnchorLocation(location = index, effectiveSize = effectiveSize)
    .let { if (it >= 0) it else abs(it + 1) }

/**
 * PropertySet implements a set which allows recording integers into a set an efficiently extracting
 * the greatest max value out of the set. It does this using the heap structure from a heap sort
 * that ensures that adding or removing a value is O(log N) operation even if values are repeatedly
 * added and removed.
 *
 * PropertySet은 정수를 집합에 기록하고, 그 집합에서 가장 큰 값을 효율적으로 추출할 수 있는 집합을
 * 구현합니다. 이는 힙 정렬에서 사용하는 힙 구조를 통해 이루어지며, 값이 반복적으로 추가되거나
 * 제거되더라도 추가나 제거 연산이 O(log N) 복잡도로 보장됩니다.
 */
// 이진 탐색으로 구현되고, 가장 큰 값이 0번째 인덱스로 옴.
@JvmInline
internal value class PrioritySet(private val list: MutableIntList = mutableIntListOf()) {
  // Add a value to the heap.
  // 힙에 값을 추가합니다.
  fun add(value: Int) {
    // Filter trivial duplicates. (trivial: 사소한, 하찮은)
    // 사소한 중복을 걸러냅니다.
    if (list.isNotEmpty() && (list.first() == value || list.last() == value))
      return

    var insertingIndex = list.size

    list.add(value)

    // Shift the value up the heap.
    // 값을 힙 위로 올립니다.
    //
    // 이진 탐색 구현 (왜 변수명이 parent 일까?)
    while (insertingIndex > 0) {
      val parent = ((insertingIndex + 1) ushr 1) - 1
      val parentValue = list[parent]

      if (value > parentValue)
        list[insertingIndex] = parentValue
      else
        break

      insertingIndex = parent
    }

    list[insertingIndex] = value
  }

  fun isEmpty(): Boolean = list.isEmpty()

  fun isNotEmpty(): Boolean = list.isNotEmpty()

  fun peek(): Int = list.first()

  // Remove a de-duplicated value from the heap.
  // 중복 제거된 값을 힙에서 제거합니다.
  //
  // 제일 큰 값 반환
  fun takeMax(): Int {
    debugRuntimeCheck(list.size > 0) { "Set is empty" }

    val value = list.first()

    // Skip duplicates. It is not time efficient to remove duplicates from the list while
    // adding so remove them when they leave the list. This also implies that the underlying
    // list's size is not an accurate size of the list so this set doesn't implement size.
    // If size is needed later consider de-duping on insert which might require companion map.
    //
    // 중복은 건너뜁니다. 추가하면서 리스트에서 중복을 제거하는 것은 시간 효율적이지 않으므로,
    // 리스트에서 빠져나올 때 제거합니다. 따라서 내부 리스트의 크기는 실제 크기를 정확히 나타내지
    // 않으며, 이 집합은 size를 구현하지 않습니다. 나중에 size가 필요하다면 삽입 시 중복 제거를
    // 고려해야 하며, 이 경우 보조 맵이 필요할 수 있습니다.
    while (list.isNotEmpty() && list.first() == value) {
      // Shift the last value down.
      // 마지막 값을 아래로 내립니다.
      list[0] = list.last()
      list.removeAt(list.lastIndex)

      var index = 0
      val size = list.size
      val maxIndex = size / 2

      while (index < maxIndex) {
        val indexValue = list[index]
        val left = (index + 1) * 2 - 1
        val leftValue = list[left]
        val right = (index + 1) * 2

        if (right < size) {
          // Note: only right can exceed size because of the constraint on index being
          // less than floor(list.size / 2).
          //
          // floor: 수 내림 (floor(7.6) -> 7)
          //
          // 인덱스가 floor(list.size / 2)보다 작아야 한다는 제약 때문에 size를 초과할
          // 수 있는 것은 right뿐입니다.
          val rightValue = list[right]
          if (rightValue > leftValue) {
            if (rightValue > indexValue) {
              list[index] = rightValue
              list[right] = indexValue
              index = right
              continue
            } else break
          }
        }

        if (leftValue > indexValue) {
          list[index] = leftValue
          list[left] = indexValue
          index = left
        } else break
      }
    }

    return value
  }

  fun validateHeap() {
    val size = list.size
    for (index in 0 until size / 2) {
      val left = (index + 1) * 2 - 1
      val right = (index + 1) * 2
      checkPrecondition(list[index] >= list[left])
      checkPrecondition(right >= size || list[index] >= list[right])
    }
  }
}

private const val LIVE_EDIT_INVALID_KEY = -3

private fun MutableIntObjectMap<MutableIntSet>.add(key: Int, value: Int) {
  (this[key] ?: MutableIntSet().also { set(key, it) }).add(value)
}

// This function exists so we do *not* inline the throw. It keeps
// the call site much smaller and since it's the slow path anyway,
// we don't mind the extra function call.
//
// 이 함수는 throw를 인라인하지 않기 위해 존재합니다. 이렇게 하면
// 호출 지점이 훨씬 작아지고, 어차피 느린 경로이므로 함수 호출이
// 추가되더라도 문제되지 않습니다.
internal fun throwConcurrentModificationException(): Nothing {
  throw ConcurrentModificationException()
}
