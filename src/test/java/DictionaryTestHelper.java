import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;

/**
 * Reads a single field out of the package's standard action-return envelope
 * (see DictionaryHelper) without repeating the map/cast boilerplate in every test.
 */
public final class DictionaryTestHelper {

    private DictionaryTestHelper() {
    }

    public static String field(DictionaryValue dictionary, String key) {
        Value value = dictionary.get().get(key);
        return value == null ? null : ((StringValue) value).get();
    }

    public static String result(DictionaryValue dictionary) {
        return field(dictionary, "result");
    }
}
