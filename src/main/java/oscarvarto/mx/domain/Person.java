package oscarvarto.mx.domain;

import fj.data.NonEmptyList;
import fj.data.Validation;
import lombok.Value;
import org.checkerframework.checker.nullness.qual.NonNull;
import oscarvarto.mx.qual.ErrorMsg;
import oscarvarto.mx.qual.Name;

import static fj.Semigroup.nonEmptyListSemigroup;
import static oscarvarto.mx.validation.ValidationOps.check;

@Value
public class Person {
    private final @Name String name;

    int age;

    public static final int MAX_AGE = 130;
    public static final @ErrorMsg String NAME_EMPTY_OR_WHITESPACE_ERROR_MSG =
            "Name cannot be empty or contain only white space";
    public static final @ErrorMsg String NEGATIVE_AGE_ERROR_MSG = "Age cannot be negative";

    @SuppressWarnings("assignment") // string concatenation is not a literal for @QualifierForLiterals
    public static final @ErrorMsg String MAX_AGE_ERROR_MSG = "Age cannot be bigger than " + MAX_AGE + " years";

    public static Validation<NonEmptyList<@ErrorMsg String>, Person> of(@NonNull String name, int age) {
        var validatedName = check(!name.isBlank(), NAME_EMPTY_OR_WHITESPACE_ERROR_MSG, name);
        var validatedMinAge = check(age >= 0, NEGATIVE_AGE_ERROR_MSG, age);
        var validatedMaxAge = check(age <= MAX_AGE, MAX_AGE_ERROR_MSG, age);
        @SuppressWarnings("argument") // validation guarantees n is a valid @Name String
        var result = validatedName.accumulate(
                nonEmptyListSemigroup(), validatedMinAge, validatedMaxAge, (n, a1, a2) -> new Person(n, a1));
        return result;
    }
}
