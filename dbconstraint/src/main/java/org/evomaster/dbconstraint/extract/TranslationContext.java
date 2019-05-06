package org.evomaster.dbconstraint.extract;

public class TranslationContext {

    private final String currentTableName;

    public TranslationContext(String currentTableName) {
        this.currentTableName = currentTableName;
    }

    public String getCurrentTableName() {
        return currentTableName;
    }
}
