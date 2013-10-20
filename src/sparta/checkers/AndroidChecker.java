package sparta.checkers;

import checkers.source.AggregateChecker;
import checkers.source.SourceChecker;

import java.util.ArrayList;
import java.util.Collection;

/**
 * An aggregate checker calling all Android type checkers.
 */
public class AndroidChecker extends AggregateChecker {

    @Override
    protected Collection<Class<? extends SourceChecker>> getSupportedCheckers() {
        Collection<Class<? extends SourceChecker>> checkers = new ArrayList<Class<? extends SourceChecker>>(
                2);
        checkers.add(AndroidFenumChecker.class);
        checkers.add(RequiredPermissionsChecker.class);
        checkers.add(AndroidReportChecker.class);
        checkers.add(FlowChecker.class);
        return checkers;
    }
}