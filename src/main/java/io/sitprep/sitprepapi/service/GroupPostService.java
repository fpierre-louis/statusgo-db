package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.util.GroupUrlUtil;
import io.sitprep.sitprepapi.util.PublicCdn;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupPostDto;
import io.sitprep.sitprepapi.dto.PostReactionDto;
import io.sitprep.sitprepapi.dto.GroupPostSummaryDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;

import jakarta.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GroupPost create/update/read. Image bytes are no longer handled here —
 * clients upload via {@code POST /api/images} (Cloudflare R2), then
 * attach the returned {@code imageKey} to the post on create/edit.
 * Public delivery URLs are derived via {@link PublicCdn}.
 */
@Service
public class GroupPostService {

    private static final Logger logger = LoggerFactory.getLogger(GroupPostService.class);

    private final GroupPostRepo postRepo;
    private final UserInfoRepo userInfoRepo;
    private final GroupRepo groupRepo;
    private final NotificationService notificationService;
    private final WebSocketMessageSender webSocketMessageSender;
    private final GroupPostReactionService reactionService;

    @Autowired
    public GroupPostService(GroupPostRepo postRepo, UserInfoRepo userInfoRepo, GroupRepo groupRepo,
                       NotificationService notificationService,
                       WebSocketMessageSender webSocketMessageSender,
                       GroupPostReactionService reactionService) {
        this.postRepo = postRepo;
        this.userInfoRepo = userInfoRepo;
        this.groupRepo = groupRepo;
        this.notificationService = notificationService;
        this.webSocketMessageSender = webSocketMessageSender;
        this.reactionService = reactionService;
    }

    /** REST creation. Body carries content/group + optional imageKey from /api/images. */
    @Transactional
    public GroupPostDto createPost(GroupPostDto postDto, String actorEmail) {
        if (!actorEmail.equalsIgnoreCase(postDto.getAuthor())) {
            throw new SecurityException("User not authorized to create a post for another user.");
        }

        GroupPost post = new GroupPost();
        post.setAuthor(postDto.getAuthor());
        post.setContent(postDto.getContent());
        post.setGroupId(postDto.getGroupId());
        post.setGroupName(postDto.getGroupName());
        post.setTimestamp(Instant.now());
        post.setTags(postDto.getTags());
        post.setMentions(postDto.getMentions());
        if (postDto.getImageKey() != null && !postDto.getImageKey().isBlank()) {
            post.setImageKey(postDto.getImageKey().trim());
        }

        GroupPost savedPost = postRepo.save(post);
        GroupPostDto savedDto = convertToPostDto(savedPost);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    notifyGroupMembersOfNewPost(savedPost);
                    webSocketMessageSender.sendNewPost(savedPost.getGroupId(), savedDto);
                } catch (Exception e) {
                    logger.error("GroupPost-commit WS/notify error for post {}", savedPost.getId(), e);
                }
            }
        });

        return savedDto;
    }

    /** WS-path creation (text + optional imageKey). Same shape as REST. */
    @Transactional
    public GroupPostDto createPostFromDto(GroupPostDto postDto, String actorEmail) {
        return createPost(postDto, actorEmail);
    }

    @Transactional
    public GroupPost updatePost(GroupPost post, String actorEmail) {
        if (post.getAuthor() == null || !post.getAuthor().equalsIgnoreCase(actorEmail)) {
            throw new SecurityException("User not authorized to update this post.");
        }

        GroupPost updatedPost = postRepo.save(post);
        GroupPostDto updatedPostDto = convertToPostDto(updatedPost);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                webSocketMessageSender.sendNewPost(updatedPost.getGroupId(), updatedPostDto);
            }
        });

        return updatedPost;
    }

    @Transactional
    public void deletePostAndBroadcast(Long postId, String actorEmail) {
        GroupPost post = postRepo.findById(postId).orElseThrow(() -> new RuntimeException("GroupPost not found"));

        if (post.getAuthor() == null || !post.getAuthor().equalsIgnoreCase(actorEmail)) {
            throw new SecurityException("User not authorized to delete this post.");
        }

        postRepo.delete(post);
        webSocketMessageSender.sendPostDeletion(post.getGroupId(), post.getId());
    }

    @Transactional
    public void updatePostFromDto(GroupPostDto dto) {
        String actorEmail = dto.getAuthor(); // WS path provides author in dto

        GroupPost post = postRepo.findById(dto.getId()).orElseThrow(() ->
                new IllegalArgumentException("GroupPost not found for update: " + dto.getId()));

        if (actorEmail != null && !post.getAuthor().equalsIgnoreCase(actorEmail)) {
            throw new SecurityException("Not allowed to edit this post.");
        }

        post.setContent(dto.getContent());
        post.setEditedAt(Instant.now());
        post.setTags(dto.getTags());
        post.setMentions(dto.getMentions());

        // imageKey present → replace; absent → clear (treats omission as
        // "remove image"). Editors that want to leave the image untouched
        // should pass the existing imageKey back through.
        if (dto.getImageKey() != null && !dto.getImageKey().isBlank()) {
            post.setImageKey(dto.getImageKey().trim());
        } else {
            post.setImageKey(null);
        }

        GroupPost updated = postRepo.save(post);
        GroupPostDto updatedDto = convertToPostDto(updated);
        updatedDto.setTempId(dto.getTempId());

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                webSocketMessageSender.sendNewPost(updated.getGroupId(), updatedDto);
            }
        });
    }

    public List<GroupPost> getPostsByGroupId(String groupId) { return postRepo.findPostsByGroupId(groupId); }

    public List<GroupPostDto> getPostsByGroupSince(String groupId, Instant since) {
        List<GroupPost> rows = postRepo.findByGroupIdAndUpdatedAtAfterOrderByUpdatedAtAsc(groupId, since);
        if (rows.isEmpty()) return List.of();

        Set<String> emails = rows.stream().map(GroupPost::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails)).stream()
                .collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        Map<Long, Map<String, List<PostReactionDto>>> reactionsByPost =
                reactionService.loadByPostIds(rows.stream().map(GroupPost::getId).toList());

        List<GroupPostDto> out = new ArrayList<>(rows.size());
        for (GroupPost p : rows) out.add(convertToPostDto(p, userByEmail, reactionsByPost.get(p.getId())));
        return out;
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<GroupPostDto> getPostsByGroupIdDto(String groupId) {
        List<GroupPost> posts = postRepo.findPostsByGroupId(groupId);
        if (posts.isEmpty()) return List.of();

        Set<String> emails = posts.stream().map(GroupPost::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails)).stream()
                .collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        // Batched reaction roster — one repo call for the whole listing.
        Map<Long, Map<String, List<PostReactionDto>>> reactionsByPost =
                reactionService.loadByPostIds(posts.stream().map(GroupPost::getId).toList());

        return posts.stream()
                .map(p -> convertToPostDto(p, userByEmail, reactionsByPost.get(p.getId())))
                .toList();
    }

    public Optional<GroupPost> getPostById(Long id) { return postRepo.findById(id); }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<GroupPostDto> getPostDtoById(Long id) { return postRepo.findById(id).map(this::convertToPostDto); }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Map<String, GroupPostSummaryDto> getLatestPostsForGroups(List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) return Collections.emptyMap();

        List<GroupPost> candidates = postRepo.findLatestPostsByGroupIds(groupIds);
        Map<String, GroupPost> bestByGroup = new HashMap<>();
        for (GroupPost p : candidates) {
            GroupPost cur = bestByGroup.get(p.getGroupId());
            if (cur == null || p.getTimestamp().isAfter(cur.getTimestamp())
                    || (p.getTimestamp().equals(cur.getTimestamp()) && p.getId() > cur.getId())) {
                bestByGroup.put(p.getGroupId(), p);
            }
        }

        Set<String> emails = bestByGroup.values().stream().map(GroupPost::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails)).stream()
                .collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        Map<String, GroupPostSummaryDto> out = new HashMap<>();
        for (var e : bestByGroup.entrySet()) {
            GroupPost p = e.getValue();
            UserInfo u = userByEmail.get(p.getAuthor());

            GroupPostSummaryDto dto = new GroupPostSummaryDto();
            dto.setId(p.getId());
            dto.setGroupId(p.getGroupId());
            dto.setGroupName(p.getGroupName());
            dto.setAuthor(p.getAuthor());
            if (u != null) {
                dto.setAuthorFirstName(u.getUserFirstName());
                dto.setAuthorLastName(u.getUserLastName());
                dto.setAuthorProfileImageURL(u.getProfileImageURL());
            }
            dto.setContent(p.getContent());
            dto.setTimestamp(p.getTimestamp());

            out.put(e.getKey(), dto);
        }
        return out;
    }

    private void notifyGroupMembersOfNewPost(GroupPost post) {
        groupRepo.findByGroupId(post.getGroupId()).ifPresentOrElse(group -> {
            var recipientEmails = group.getMemberEmails() == null ? List.<String>of() :
                    group.getMemberEmails().stream().filter(e -> !e.equalsIgnoreCase(post.getAuthor())).toList();

            if (recipientEmails.isEmpty()) {
                logger.warn("No recipients for group {}", group.getGroupName());
                return;
            }

            var authorOpt = userInfoRepo.findByUserEmail(post.getAuthor());
            String authorFirst = authorOpt.map(UserInfo::getUserFirstName).orElse("Someone");
            String authorProfile = authorOpt.map(UserInfo::getProfileImageURL).orElse("/images/default-user-icon.png");

            String title = group.getGroupName();
            String snippet = post.getContent() == null ? "" :
                    (post.getContent().length() > 50 ? post.getContent().substring(0, 50) + "..." : post.getContent());
            String body = String.format("%s posted in %s: '%s'", authorFirst, group.getGroupName(), snippet);

            String baseTargetUrl = GroupUrlUtil.getGroupTargetUrl(group);
            String targetUrl = baseTargetUrl + "?postId=" + post.getId();

            List<UserInfo> users = userInfoRepo.findByUserEmailIn(recipientEmails);
            for (UserInfo user : users) {
                notificationService.deliverPresenceAware(
                        user.getUserEmail(), title, body, authorFirst, authorProfile,
                        "post_notification", post.getGroupId(), targetUrl, String.valueOf(post.getId()),
                        user.getFcmtoken()
                );
            }

            logger.info("GroupPost notification sent for '{}' to {} members.", group.getGroupName(), users.size());
        }, () -> logger.warn("Group with ID {} not found (notify)", post.getGroupId()));
    }

    private GroupPostDto convertToPostDto(GroupPost post) {
        GroupPostDto dto = baseDto(post, reactionService.loadByPostId(post.getId()));
        userInfoRepo.findByUserEmail(post.getAuthor()).ifPresent(u -> {
            dto.setAuthorFirstName(u.getUserFirstName());
            dto.setAuthorLastName(u.getUserLastName());
            dto.setAuthorProfileImageURL(u.getProfileImageURL());
        });
        return dto;
    }

    private GroupPostDto convertToPostDto(GroupPost post,
                                     Map<String, UserInfo> userByEmail,
                                     Map<String, List<PostReactionDto>> reactions) {
        GroupPostDto dto = baseDto(post, reactions);
        UserInfo u = userByEmail.get(post.getAuthor());
        if (u != null) {
            dto.setAuthorFirstName(u.getUserFirstName());
            dto.setAuthorLastName(u.getUserLastName());
            dto.setAuthorProfileImageURL(u.getProfileImageURL());
        }
        return dto;
    }

    private GroupPostDto baseDto(GroupPost post, Map<String, List<PostReactionDto>> reactions) {
        GroupPostDto dto = new GroupPostDto();
        dto.setId(post.getId());
        dto.setAuthor(post.getAuthor());
        dto.setContent(post.getContent());
        dto.setGroupId(String.valueOf(post.getGroupId()));
        dto.setGroupName(post.getGroupName());
        dto.setTimestamp(post.getTimestamp());
        dto.setEditedAt(post.getEditedAt());
        dto.setUpdatedAt(post.getUpdatedAt());
        if (post.getImageKey() != null && !post.getImageKey().isBlank()) {
            dto.setImageKey(post.getImageKey());
            dto.setImageUrl(PublicCdn.toPublicUrl(post.getImageKey()));
        }
        dto.setReactions(reactions != null ? reactions : new HashMap<>());
        dto.setTags(post.getTags() != null ? post.getTags() : new ArrayList<>());
        dto.setMentions(post.getMentions() != null ? post.getMentions() : new ArrayList<>());
        dto.setCommentsCount(post.getCommentsCount());
        return dto;
    }
}
