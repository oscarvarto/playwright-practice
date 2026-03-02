package oscarvarto.mx.validation;

import fj.data.NonEmptyList;
import fj.data.Validation;
import oscarvarto.mx.qual.ErrorMsg;

import static fj.Monoid.stringMonoid;
import static fj.data.Validation.condition;

public class ValidationOps {
    private ValidationOps() {}
    ;

    public static <T> Validation<NonEmptyList<@ErrorMsg String>, T> check(
            final boolean c, final @ErrorMsg String e, final T t) {
        return condition(c, e, t).nel();
    }

    /// Joins error messages with comma separator.
    ///
    /// @param errors NonEmptyList of error messages
    /// @return Comma-separated error string
    @SuppressWarnings({"argument", "assignment", "cast.unsafe"})
    public static @ErrorMsg String joinErrors(NonEmptyList<@ErrorMsg String> errors) {
        return (@ErrorMsg String) stringMonoid.join(errors, ", ");
    }
}
