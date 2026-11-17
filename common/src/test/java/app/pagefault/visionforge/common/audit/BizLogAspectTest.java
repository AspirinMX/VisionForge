package app.pagefault.visionforge.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.common.id.UuidV7Generator;
import app.pagefault.visionforge.common.logging.TraceContext;

class BizLogAspectTest {

    private static final UUID OPERATOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private AnnotationConfigApplicationContext context;
    private TestService service;
    private CapturingBizLogStore store;

    @BeforeEach
    void setUp() {
        TraceContext.setTraceId("trace-12345678");
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        service = context.getBean(TestService.class);
        store = context.getBean(CapturingBizLogStore.class);
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
        context.close();
    }

    @Test
    void recordsSuccessfulOperationWithTemplateAndMasking() {
        TestUser user = new TestUser("u-1", "alice", "alice@example.com");

        TestUser result = service.create(user);

        assertThat(result).isSameAs(user);
        assertThat(store.records()).hasSize(1);
        BizLogRecord record = store.records().get(0);
        assertThat(record.id().version()).isEqualTo(7);
        assertThat(record.type()).isEqualTo("TEST_USER");
        assertThat(record.subType()).isEqualTo("CREATE");
        assertThat(record.bizNo()).isEqualTo("u-1");
        assertThat(record.operatorId()).isEqualTo(OPERATOR_ID);
        assertThat(record.operatorName()).isEqualTo("Alice");
        assertThat(record.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(record.success()).isTrue();
        assertThat(record.detail()).isEqualTo("用户「alice」创建成功，邮箱=a***@example.com");
        assertThat(record.traceId()).isEqualTo("trace-12345678");
        assertThat(record.errorMessage()).isNull();
        assertThat(record.createdAt()).isEqualTo(Instant.parse("2026-11-17T12:00:00Z"));
    }

    @Test
    void recordsFailureAndRethrowsOriginalException() {
        TestUser user = new TestUser("u-2", "bob", "bob@example.com");

        assertThatThrownBy(() -> service.fail(user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(store.records()).hasSize(1);
        BizLogRecord record = store.records().get(0);
        assertThat(record.success()).isFalse();
        assertThat(record.bizNo()).isEqualTo("u-2");
        assertThat(record.detail()).isEqualTo("创建失败：boom");
        assertThat(record.errorMessage()).isEqualTo("boom");
    }

    @Test
    void skipsFailureRecordWhenDisabled() {
        assertThatThrownBy(() -> service.ignoreFailure()).isInstanceOf(IllegalStateException.class);

        assertThat(store.records()).isEmpty();
    }

    @Test
    void respectsCondition() {
        service.conditional(true);
        service.conditional(false);

        assertThat(store.records()).hasSize(1);
        assertThat(store.records().get(0).subType()).isEqualTo("CONDITIONAL");
    }

    @Test
    void storesAsynchronouslyByDefault() throws InterruptedException {
        store.expect(1);

        service.createAsync(new TestUser("u-3", "carol", "carol@example.com"));

        assertThat(store.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(store.records()).hasSize(1);
        assertThat(store.records().get(0).subType()).isEqualTo("CREATE_ASYNC");
    }

    @Configuration
    @Import(BizLogConfiguration.class)
    static class TestConfiguration {

        @Bean
        TestService testService() {
            return new TestService();
        }

        @Bean
        CapturingBizLogStore bizLogStore() {
            return new CapturingBizLogStore();
        }

        @Bean
        OperatorProvider operatorProvider() {
            return () -> new Operator(OPERATOR_ID, "Alice");
        }

        @Bean
        TenantProvider tenantProvider() {
            return () -> WORKSPACE_ID;
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        IdGenerator idGenerator(Clock clock) {
            return new UuidV7Generator(clock);
        }
    }

    static class CapturingBizLogStore implements BizLogStore {

        private final List<BizLogRecord> records = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch latch = new CountDownLatch(0);

        void expect(int count) {
            latch = new CountDownLatch(count);
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        List<BizLogRecord> records() {
            return records;
        }

        @Override
        public void save(BizLogRecord record) {
            records.add(record);
            latch.countDown();
        }
    }

    static class TestService {

        @BizLog(
                type = "TEST_USER",
                subType = "CREATE",
                bizNo = "#result.id",
                success = "用户「{{#result.name}}」创建成功，邮箱={{#maskEmail(#result.email)}}",
                sync = true)
        public TestUser create(TestUser user) {
            return user;
        }

        @BizLog(
                type = "TEST_USER",
                subType = "CREATE_ASYNC",
                bizNo = "#result.id",
                success = "异步创建 {{#result.name}}")
        public TestUser createAsync(TestUser user) {
            return user;
        }

        @BizLog(
                type = "TEST_USER",
                subType = "FAIL",
                bizNo = "#user.id",
                fail = "创建失败：{{#errorMessage}}",
                sync = true)
        public TestUser fail(TestUser user) {
            throw new IllegalStateException("boom");
        }

        @BizLog(type = "TEST_USER", subType = "IGNORE_FAILURE", recordOnFailure = false, sync = true)
        public void ignoreFailure() {
            throw new IllegalStateException("ignored");
        }

        @BizLog(
                type = "TEST_USER",
                subType = "CONDITIONAL",
                condition = "#enabled",
                success = "recorded",
                sync = true)
        public void conditional(boolean enabled) {
        }
    }

    record TestUser(String id, String name, String email) {
    }
}
