/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.compose.runtime

/**
 * An annotation generated by the compose compiler plugin. Do not use explicitly.
 *
 * The [ComposableInferredTarget] annotation is an abbreviated form of [ComposableTarget] and
 * [ComposableOpenTarget] generated by the compose compiler plugin when it infers the target of a
 * composable function that has one or more composable lambda parameters.
 *
 * This is intended to only be generated by the plugin and should not be used directly. Use
 * [ComposableOpenTarget] and [ComposableTarget] instead.
 *
 * This annotation is used by the Compose compiler plugin because Kotlin compiler plugins are a not
 * allowed to add annotations to types such as the lambda types of a [Composable] function
 * parameter. This annotation records which annotation would have been added.
 */
@InternalComposeApi
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY_GETTER,
)
annotation class ComposableInferredTarget(val scheme: String)
