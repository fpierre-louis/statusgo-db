package io.sitprep.sitprepapi.websocket;

import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.service.GroupPostService;
import io.sitprep.sitprepapi.service.PlanActivationService;
import io.sitprep.sitprepapi.service.PostReadAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SUBSCRIBE authorization on the per-activation acks topic (2026-07-07
 * hardening): the activationId rides in the share link, so the topic name is
 * guessable by any link holder — the subscribe itself must be authorized.
 * Pure Mockito, no Spring context.
 *
 * <p>Updated when SUBSCRIBE moved from default-allow to default-deny; the
 * rules now live in {@link WebSocketTopicAuthorizer}. The acks expectations
 * below are unchanged — they are the behaviour the 2026-07-07 pass added and
 * this change preserves it exactly. Broader per-family coverage lives with
 * the read-authorization regression tests.</p>
 */
class WebSocketAuthChannelInterceptorSubscribeTest {

    private PlanActivationService service;
    private WebSocketAuthChannelInterceptor interceptor;
    private final MessageChannel channel = mock(MessageChannel.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = mock(PlanActivationService.class);
        ObjectProvider<PlanActivationService> activationProvider = mock(ObjectProvider.class);
        when(activationProvider.getObject()).thenReturn(service);

        ObjectProvider<GroupPostService> groupPostProvider = mock(ObjectProvider.class);
        when(groupPostProvider.getObject()).thenReturn(mock(GroupPostService.class));

        WebSocketTopicAuthorizer authorizer = new WebSocketTopicAuthorizer(
                groupPostProvider,
                activationProvider,
                mock(PostRepo.class),
                mock(PostReadAuthorizer.class));
        interceptor = new WebSocketAuthChannelInterceptor(authorizer);
    }

    private Message<byte[]> subscribe(String destination, String principalEmail) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        if (principalEmail != null) {
            accessor.setUser(new StompPrincipal(principalEmail));
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void acksTopic_allowsAuthorizedReader() {
        when(service.canReadActivationAcks("act-1", "owner@x.com")).thenReturn(true);

        assertNotNull(interceptor.preSend(
                subscribe("/topic/activations/act-1/acks", "owner@x.com"), channel));
        verify(service).canReadActivationAcks("act-1", "owner@x.com");
    }

    @Test
    void acksTopic_rejectsUnauthorizedIdentity() {
        when(service.canReadActivationAcks("act-1", "attacker@x.com")).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(
                subscribe("/topic/activations/act-1/acks", "attacker@x.com"), channel));
    }

    @Test
    void acksTopic_rejectsSessionWithNoIdentity() {
        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(
                subscribe("/topic/activations/act-1/acks", null), channel));
        verifyNoInteractions(service);
    }

    @Test
    void activationPlanTopic_keepsLinkPossessionContract() {
        // Plan-update frames carry no PII; recipients (incl. guests once
        // anonymous WS ships) must keep receiving them without a reader gate.
        assertNotNull(interceptor.preSend(
                subscribe("/topic/activations/act-1/plan", null), channel));
        verifyNoInteractions(service);
    }

    @Test
    void communityTopic_staysOpenToAnyAuthenticatedSession() {
        // The neighborhood stream — same content GET /api/community/posts
        // already serves to every signed-in user.
        assertNotNull(interceptor.preSend(
                subscribe("/topic/community/posts/policy-90210", "anyone@x.com"), channel));
    }

    @Test
    void retiredGlobalNotificationsTopic_isNowRejected() {
        // Previously allowed only because SUBSCRIBE was default-allow. The
        // frontend retired this destination precisely because it "leaked every
        // user's" notifications (SocketNotificationsBridge.js:19) and the
        // backend never publishes to it — per-user
        // /topic/notifications/{email}/banner replaced it. Under default-deny
        // an unclassified destination fails closed, which is the correct
        // outcome for a topic that exists only in stale documentation.
        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(
                subscribe("/topic/notifications", null), channel));
    }

    @Test
    void unknownTopic_failsClosed() {
        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(
                subscribe("/topic/some/future/topic", "anyone@x.com"), channel));
    }

    /**
     * Default-deny's one real hazard is a destination the rules fail to
     * classify: it would fail closed and silently break a live feature. This
     * runs every destination shape the frontend actually subscribes to
     * (enumerated from {@code grep -roE '/topic/[^"`]*' src/}) through a
     * fully-permissive authorizer, so the only thing that can fail here is
     * classification coverage, not authorization logic.
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyFrontendDestinationShapeIsClassified() {
        GroupPostService groupPosts = mock(GroupPostService.class);
        when(groupPosts.canReadGroup(anyString(), anyString())).thenReturn(true);
        when(groupPosts.getPostById(anyLong()))
                .thenReturn(java.util.Optional.of(new io.sitprep.sitprepapi.domain.GroupPost()));
        when(groupPosts.canRead(any(), anyString())).thenReturn(true);
        ObjectProvider<GroupPostService> groupPostProvider = mock(ObjectProvider.class);
        when(groupPostProvider.getObject()).thenReturn(groupPosts);

        PlanActivationService activations = mock(PlanActivationService.class);
        when(activations.canReadActivationAcks(anyString(), anyString())).thenReturn(true);
        ObjectProvider<PlanActivationService> activationProvider = mock(ObjectProvider.class);
        when(activationProvider.getObject()).thenReturn(activations);

        PostRepo postRepo = mock(PostRepo.class);
        when(postRepo.findById(anyLong()))
                .thenReturn(java.util.Optional.of(new io.sitprep.sitprepapi.domain.Post()));
        PostReadAuthorizer postAuth = mock(PostReadAuthorizer.class);
        when(postAuth.canRead(any(), anyString())).thenReturn(true);

        WebSocketTopicAuthorizer permissive = new WebSocketTopicAuthorizer(
                groupPostProvider, activationProvider, postRepo, postAuth);

        String[] destinations = {
                "/topic/activations/act-1/acks",
                "/topic/activations/act-1/plan",
                "/topic/community/posts/902",
                "/topic/community/posts/902/delete",
                "/topic/dm/me@x.com",
                "/topic/group-post-comments/42",
                "/topic/group-post-comments/42/delete",
                "/topic/group-posts/g-1",
                "/topic/group-posts/g-1/delete",
                "/topic/group-posts/g-1/typing",
                "/topic/group/g-1/members",
                "/topic/group/g-1/members/location",
                "/topic/group/g-1/members/status",
                "/topic/group/g-1/posts",
                "/topic/group/g-1/posts/delete",
                "/topic/group/g-1/status",
                "/topic/households/h-1/accompaniments",
                "/topic/households/h-1/accompaniments/release",
                "/topic/households/h-1/accompaniments/snapshot",
                "/topic/households/h-1/demographic",
                "/topic/households/h-1/events",
                "/topic/households/h-1/manual-members",
                "/topic/households/h-1/manual-members/delete",
                "/topic/households/h-1/members/status",
                "/topic/households/h-1/presence",
                "/topic/households/h-1/supplies",
                "/topic/notifications/me@x.com",
                "/topic/notifications/me@x.com/banner",
                "/topic/post-comments/42",
                "/topic/post-comments/42/delete",
        };

        for (String destination : destinations) {
            assertTrue(permissive.canSubscribe(destination, "me@x.com"),
                    "unclassified destination would fail closed and break a live "
                            + "subscription: " + destination);
        }
    }

    @Test
    void userScopedTopic_allowsSelfAndRejectsOthers() {
        assertNotNull(interceptor.preSend(
                subscribe("/topic/notifications/me@x.com/banner", "me@x.com"), channel));
        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(
                subscribe("/topic/notifications/victim@x.com/banner", "attacker@x.com"), channel));
        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(
                subscribe("/topic/dm/victim@x.com", "attacker@x.com"), channel));
    }
}
