package oscarvarto.mx.qual;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.checkerframework.framework.qual.DefaultFor;
import org.checkerframework.framework.qual.LiteralKind;
import org.checkerframework.framework.qual.QualifierForLiterals;
import org.checkerframework.framework.qual.SubtypeOf;
import org.checkerframework.framework.qual.TypeUseLocation;

/// Denotes that a String is an error message used for error accumulation.
///
/// Use this annotation on strings that are intended to be accumulated in error-handling
/// structures like `NonEmptyList<@ErrorMsg String>` via semigroups.
///
/// Type hierarchy:
///
/// ```
///    @NotErrorMsg (top)
///        |
///    @ErrorMsg <-- THIS
/// ```
///
/// Example usage:
///
/// ```java
/// // Explicitly mark error messages
/// @ErrorMsg String error = "Missing path parameter: " + paramName;
///
/// // Accumulate errors using semigroup
/// Semigroup<NonEmptyList<@ErrorMsg String>> errorSemigroup = Semigroup.nonEmptyListSemigroup();
/// ```
///
/// @see NotErrorMsg
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
@DefaultFor({TypeUseLocation.LOWER_BOUND})
@QualifierForLiterals(LiteralKind.STRING)
@SubtypeOf(NotErrorMsg.class)
public @interface ErrorMsg {}
