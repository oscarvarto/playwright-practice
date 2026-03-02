package oscarvarto.mx.qual;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.checkerframework.framework.qual.DefaultQualifierInHierarchy;
import org.checkerframework.framework.qual.SubtypeOf;

/// Denotes that a String is NOT an error message (top of hierarchy, default).
///
/// This is the DEFAULT qualifier for unannotated types, enforcing strong separation between error
/// message strings and regular strings. If you don't see `@ErrorMsg` written explicitly on a
/// type, you will know that it does not contain error message values.
///
/// Type hierarchy:
///
/// ```
///    @NotErrorMsg (top, default)  <-- THIS
///         |
///    @ErrorMsg (subtype)
/// ```
///
/// @see ErrorMsg
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
@DefaultQualifierInHierarchy
@SubtypeOf({})
public @interface NotErrorMsg {}
