package ch.admin.bit.jeap.mcp.execution;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class McpRequestSizeFilterTest {

    private final McpRequestSizeFilter filter = new McpRequestSizeFilter(100);

    @Test
    void negativeMaxBytesIsRejectedAtConstruction() {
        assertThatIllegalArgumentException().isThrownBy(() -> new McpRequestSizeFilter(-1));
    }

    @Test
    void maxBytesAboveIntRangeIsRejectedAtConstruction() {
        assertThatIllegalArgumentException().isThrownBy(() -> new McpRequestSizeFilter((long) Integer.MAX_VALUE));
    }

    @Test
    void requestWithinLimitPassesWithReadableBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContent("a".repeat(100).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        HttpServletRequest passedOn = (HttpServletRequest) chain.getRequest();
        assertThat(new String(passedOn.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).hasSize(100);
    }

    @Test
    void declaredContentLengthAboveLimitIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContent("a".repeat(101).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void bodyAboveLimitWithoutContentLengthIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp") {
            @Override
            public long getContentLengthLong() {
                return -1; // chunked: unknown length
            }
        };
        request.setContent("a".repeat(5000).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("REQUEST_TOO_LARGE");
        assertThat(chain.getRequest()).isNull();
    }
}
