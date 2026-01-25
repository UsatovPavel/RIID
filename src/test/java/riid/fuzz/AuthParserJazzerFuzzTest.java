package riid.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Tag;
import riid.client.core.model.auth.AuthParser;

@Tag("stress")
class AuthParserJazzerFuzzTest {

    @FuzzTest
    void fuzzAuthHeader(FuzzedDataProvider data) {
        String header = data.consumeString(512);
        AuthParser.parse(header);
    }
}



