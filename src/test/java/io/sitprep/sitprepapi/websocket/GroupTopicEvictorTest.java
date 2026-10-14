package io.sitprep.sitprepapi.websocket;

import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpSubscriptionMatcher;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-13: a removed member stops receiving the group's frames at once.
 *
 * <p>Authorization ran only at SUBSCRIBE, so a member removed from a group kept
 * hearing its chat, roster (with emails), member status and member location
 * until their socket happened to reconnect. This drives a REAL simple broker:
 * two sessions subscribe to the same group topic, the removed account's
 * subscription is evicted, and the next frame reaches only the other one.</p>
 */
class GroupTopicEvictorTest {

    private ExecutorSubscribableChannel clientInbound;
    private ExecutorSubscribableChannel brokerChannel;
    private final List<Message<?>> delivered = new ArrayList<>();
    private SimpleBrokerMessageHandler broker;
    private final Set<SimpSubscription> registered = new LinkedHashSet<>();
    private final Set<String> connected = new LinkedHashSet<>();
    private GroupTopicEvictor evictor;
    private final List<GroupPost> posts = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        clientInbound = new ExecutorSubscribableChannel();
        brokerChannel = new ExecutorSubscribableChannel();
        MessageChannel clientOutbound = new MessageChannel() {
            @Override public boolean send(Message<?> message, long timeout) {
                delivered.add(message);
                return true;
            }
        };
        broker = new SimpleBrokerMessageHandler(clientInbound, clientOutbound, brokerChannel, List.of("/topic"));
        broker.start();

        SimpUserRegistry registry = mock(SimpUserRegistry.class);
        when(registry.findSubscriptions(any(SimpSubscriptionMatcher.class))).thenAnswer(i -> {
            SimpSubscriptionMatcher m = i.getArgument(0);
            Set<SimpSubscription> out = new LinkedHashSet<>();
            for (SimpSubscription s : registered) if (m.match(s)) out.add(s);
            return out;
        });
        ObjectProvider<SimpUserRegistry> registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable()).thenReturn(registry);
        ObjectProvider<MessageChannel> channelProvider = mock(ObjectProvider.class);
        when(channelProvider.getIfAvailable()).thenReturn(brokerChannel);
        GroupPostRepo postRepo = mock(GroupPostRepo.class);
        when(postRepo.findAllById(anyIterable())).thenAnswer(i -> {
            Iterable<Long> ids = i.getArgument(0);
            List<GroupPost> out = new ArrayList<>();
            for (Long id : ids) for (GroupPost p : posts) if (p.getId().equals(id)) out.add(p);
            return out;
        });
        ObjectProvider<GroupPostRepo> postRepoProvider = mock(ObjectProvider.class);
        when(postRepoProvider.getIfAvailable()).thenReturn(postRepo);
        evictor = new GroupTopicEvictor(registryProvider, channelProvider, postRepoProvider);
    }

    private void post(long id, String groupId) {
        GroupPost p = new GroupPost();
        p.setId(id);
        p.setGroupId(groupId);
        posts.add(p);
    }

    @AfterEach
    void tearDown() {
        broker.stop();
    }

    /** A client SUBSCRIBE through the broker, and the registry entry Spring would record for it. */
    private void subscribe(String sessionId, String user, String subId, String destination) {
        if (connected.add(sessionId)) {
            // The broker only delivers to sessions it saw CONNECT.
            SimpMessageHeaderAccessor c = SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT);
            c.setSessionId(sessionId);
            clientInbound.send(MessageBuilder.createMessage(new byte[0], c.getMessageHeaders()));
        }
        SimpMessageHeaderAccessor h = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        h.setSessionId(sessionId);
        h.setSubscriptionId(subId);
        h.setDestination(destination);
        clientInbound.send(MessageBuilder.createMessage(new byte[0], h.getMessageHeaders()));
        registered.add(subscription(sessionId, user, subId, destination));
    }

    private static SimpSubscription subscription(String sessionId, String user, String subId, String destination) {
        SimpUser simpUser = mock(SimpUser.class);
        when(simpUser.getName()).thenReturn(user);
        SimpSession session = mock(SimpSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getUser()).thenReturn(simpUser);
        SimpSubscription sub = mock(SimpSubscription.class);
        when(sub.getId()).thenReturn(subId);
        when(sub.getDestination()).thenReturn(destination);
        when(sub.getSession()).thenReturn(session);
        return sub;
    }

    private List<String> recipientsOf(String destination) {
        delivered.clear();
        brokerChannel.send(MessageBuilder.withPayload("frame".getBytes())
                .setHeader(SimpMessageHeaderAccessor.DESTINATION_HEADER, destination)
                .setHeader(SimpMessageHeaderAccessor.MESSAGE_TYPE_HEADER, SimpMessageType.MESSAGE)
                .build());
        return delivered.stream()
                .filter(m -> SimpMessageHeaderAccessor.getMessageType(m.getHeaders()) == SimpMessageType.MESSAGE)
                .map(m -> SimpMessageHeaderAccessor.getSessionId(m.getHeaders())).toList();
    }

    @Test
    void theRemovedMemberStopsHearingTheGroupAndNobodyElseDoes() {
        subscribe("s-kim", "kim@x.com", "1", "/topic/group/g-1/members/location");
        subscribe("s-kim", "kim@x.com", "2", "/topic/group-posts/g-1");
        subscribe("s-kim", "kim@x.com", "3", "/topic/group/g-2/members");     // another group
        subscribe("s-ana", "ana@x.com", "1", "/topic/group/g-1/members/location");
        assertThat(recipientsOf("/topic/group/g-1/members/location")).containsExactlyInAnyOrder("s-kim", "s-ana");

        assertThat(evictor.evict("g-1", "KIM@x.com")).isEqualTo(2);

        assertThat(recipientsOf("/topic/group/g-1/members/location")).containsExactly("s-ana");
        assertThat(recipientsOf("/topic/group-posts/g-1")).isEmpty();
        assertThat(recipientsOf("/topic/group/g-2/members")).containsExactly("s-kim"); // untouched
    }

    @Test
    void anOpenCommentThreadOnTheGroupsPostStopsToo() {
        post(7L, "g-1");
        post(8L, "g-2");
        subscribe("s-kim", "kim@x.com", "1", "/topic/group-post-comments/7");
        subscribe("s-kim", "kim@x.com", "2", "/topic/group-post-comments/8");   // another group's post
        subscribe("s-ana", "ana@x.com", "1", "/topic/group-post-comments/7");

        assertThat(evictor.evict("g-1", "kim@x.com")).isEqualTo(1);

        assertThat(recipientsOf("/topic/group-post-comments/7")).containsExactly("s-ana");
        assertThat(recipientsOf("/topic/group-post-comments/8")).containsExactly("s-kim");
    }

    @Test
    void aDeletedGroupDropsEveryone() {
        subscribe("s-kim", "kim@x.com", "1", "/topic/households/hh-1/events");
        subscribe("s-ana", "ana@x.com", "9", "/topic/households/hh-1/events");
        assertThat(evictor.evictAll("hh-1")).isEqualTo(2);
        assertThat(recipientsOf("/topic/households/hh-1/events")).isEmpty();
    }

    @Test
    void onlyGroupKeyedTopicsMatch() {
        assertThat(GroupTopicEvictor.groupIdOf("/topic/group/g-1/members")).isEqualTo("g-1");
        assertThat(GroupTopicEvictor.groupIdOf("/topic/group-posts/g-1/typing")).isEqualTo("g-1");
        assertThat(GroupTopicEvictor.groupIdOf("/topic/households/hh-1/presence")).isEqualTo("hh-1");
        assertThat(GroupTopicEvictor.groupIdOf("/topic/community/posts/840")).isNull();
        assertThat(GroupTopicEvictor.groupIdOf("/topic/notifications/kim@x.com")).isNull();
        assertThat(GroupTopicEvictor.postIdOf("/topic/group-post-comments/7/delete")).isEqualTo(7L);
        assertThat(GroupTopicEvictor.postIdOf("/topic/post-comments/7")).isNull();
    }

    @Test
    void withoutABrokerItIsANoOp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SimpUserRegistry> none = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<MessageChannel> noChannel = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GroupPostRepo> noRepo = mock(ObjectProvider.class);
        assertThat(new GroupTopicEvictor(none, noChannel, noRepo).evict("g-1", "kim@x.com")).isZero();
    }
}
