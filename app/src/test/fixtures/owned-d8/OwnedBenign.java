package owned;

public final class OwnedBenign {
    private int counter;
    public String describe(String value) {
        try {
            counter += value.length();
            return "owned fixture " + counter;
        } catch (NullPointerException error) {
            return "owned missing value";
        }
    }
    public Runnable action() {
        return () -> counter++;
    }
}
