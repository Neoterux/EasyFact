/*
 * Copyright (C) 2026 the EasyFact contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.neoterux.easyfact.invoicing.result

/**
 * The result of an engine operation that can fail in a way the caller must handle: either [Ok]
 * carrying a value, or [Err] carrying a typed error.
 *
 * `kotlin.Result` is deliberately not used. It can only carry a `Throwable`, so the failure's
 * identity would have to be recovered by catching and inspecting exceptions. The error type is
 * part of this engine's contract, because the SRI returns structured, actionable failures and
 * the type system should preserve them all the way to the screen. No exception crosses the
 * boundary of a function that returns an [Outcome].
 */
sealed interface Outcome<out T, out E> {

    /** The operation succeeded and produced [value]. */
    data class Ok<out T>(val value: T) : Outcome<T, Nothing>

    /** The operation failed with the typed [error]. */
    data class Err<out E>(val error: E) : Outcome<Nothing, E>
}

/** The success value, or `null` when this is an [Outcome.Err]. */
fun <T, E> Outcome<T, E>.getOrNull(): T? = (this as? Outcome.Ok)?.value

/** The error, or `null` when this is an [Outcome.Ok]. */
fun <T, E> Outcome<T, E>.errorOrNull(): E? = (this as? Outcome.Err)?.error

/** Transforms the success value and leaves an error untouched. */
inline fun <T, E, R> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}
