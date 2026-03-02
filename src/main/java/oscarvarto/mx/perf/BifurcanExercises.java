package oscarvarto.mx.perf;

import fj.F;
import fj.Ord;
import fj.data.NonEmptyList;
import fj.data.Validation;
import io.lacuna.bifurcan.IntMap;
import io.lacuna.bifurcan.List;
import oscarvarto.mx.domain.Person;
import oscarvarto.mx.qual.ErrorMsg;
import oscarvarto.mx.qual.Name;

import static fj.Ord.longOrd;
import static fj.Ord.stringOrd;
import static fj.data.List.list;

public class BifurcanExercises {

    // IntMap
    static IntMap<List<@Name String>> groupByAge(
            fj.data.List<Validation<NonEmptyList<@ErrorMsg String>, Person>> people) {

        F<Person, Number> getAge = p -> (long) p.getAge();
        Ord<Number> numberOrd = longOrd.contramap(Number::longValue);
        // spotless:off
        @SuppressWarnings("argument") // Ord is contravariant: Ord<@NotName String> is safe for @Name String
        IntMap<List<@Name String>> groupedByAge = IntMap.from(
            Validation.successes(people)
                .groupBy(getAge, Person::getName, numberOrd)
                .map(names -> List.from(names.sort(stringOrd)))
                .toMutableMap());
        // spotless:on
        return groupedByAge;
    }

    void main() {
        // spotless:off
        var initialData = list(
                Person.of("Bety", 34),
                Person.of("Alex", 15),
                Person.of("Joshua", 11),
                Person.of("Oscar", 34));
        // spotless:on
        groupByAge(initialData).forEach(entry -> {
            IO.println(entry.key() + ": " + entry.value());
        });
    }
}
