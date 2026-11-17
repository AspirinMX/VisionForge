package app.pagefault.visionforge.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class BizAssertTest {

    @Test
    void returnsValueWhenAssertionPasses() {
        assertThat(BizAssert.notNull("value", CommonErrorCode.INVALID_ARGUMENT)).isEqualTo("value");
        assertThat(BizAssert.notBlank("value", CommonErrorCode.INVALID_ARGUMENT)).isEqualTo("value");
        assertThat(BizAssert.notEmpty(List.of("value"), CommonErrorCode.INVALID_ARGUMENT)).containsExactly("value");
    }

    @Test
    void throwsBusinessExceptionWhenAssertionFails() {
        assertThatThrownBy(() -> BizAssert.notNull(null, CommonErrorCode.NOT_FOUND))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> BizAssert.isTrue(false, CommonErrorCode.CONFLICT, "状态冲突"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("状态冲突");
    }
}
