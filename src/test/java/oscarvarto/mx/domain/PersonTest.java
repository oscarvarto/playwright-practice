package oscarvarto.mx.domain;

import org.junit.jupiter.api.Test;

import static fj.data.NonEmptyList.nel;
import static org.assertj.core.api.Assertions.assertThat;
import static oscarvarto.mx.domain.Person.*;

public class PersonTest {
    @Test
    public void nameCannotBeEmptyTest() {
        var validated = Person.of("", 24);
        assertThat(validated.fail()).isEqualTo(nel(NAME_EMPTY_OR_WHITESPACE_ERROR_MSG));
    }

    @Test
    public void nameCannotBeBlankTest() {
        var validated = Person.of("  ", 24);
        assertThat(validated.fail()).isEqualTo(nel(NAME_EMPTY_OR_WHITESPACE_ERROR_MSG));
    }

    @Test
    public void ageCannotBeNegativeTest() {
        var validated = Person.of("I-have-negative-age", -3);
        assertThat(validated.fail()).isEqualTo(nel(NEGATIVE_AGE_ERROR_MSG));
    }

    @Test
    public void ageCannotBeGreaterThanMaxAgeTest() {
        var validated = Person.of("I-am-more-than-130-years-old", 150);
        assertThat(validated.fail()).isEqualTo(nel(MAX_AGE_ERROR_MSG));
    }

    @Test
    public void allErrorsAccumulateTest() {
        var validated = Person.of("", 200);
        assertThat(validated.fail()).containsExactly(NAME_EMPTY_OR_WHITESPACE_ERROR_MSG, MAX_AGE_ERROR_MSG);
    }

    @Test
    public void validPersonTest() {
        var validated = Person.of("Paco de Lucía", 66);
        assertThat(validated.isSuccess());
    }
}
