package io.sitprep.sitprepapi.service.impl;

import io.sitprep.sitprepapi.domain.RSPost;
import io.sitprep.sitprepapi.dto.RSPostDto;
import io.sitprep.sitprepapi.repo.RSPostRepository;
import io.sitprep.sitprepapi.service.RSPostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RSPostServiceImpl implements RSPostService {

    private final RSPostRepository repo;

    private static RSPostDto toDto(RSPost p) {
        return RSPostDto.builder()
                .rsPostId(p.getRsPostId())
                .rsGroupId(p.getRsGroupId())
                .createdByEmail(p.getCreatedByEmail())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .content(p.getContent())
                .build();
    }

    @Override
    public List<RSPostDto> getPostsByGroup(String groupId) {
        return repo.findByRsGroupIdAndIsDeletedFalseOrderByCreatedAtDesc(groupId)
                .stream()
                .map(RSPostServiceImpl::toDto)
                .toList();
    }

    @Override
    public RSPostDto createPost(String groupId, String email, String content) {
        if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("groupId is required");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("email is required");
        if (content == null || content.trim().isBlank()) throw new IllegalArgumentException("content is required");

        RSPost saved = repo.save(RSPost.builder()
                .rsGroupId(groupId)
                .createdByEmail(email)
                .content(content.trim())
                .isDeleted(false)
                .build());

        return toDto(saved);
    }

    @Override
    public void deletePost(UUID postId, String email) {
        RSPost post = repo.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found"));

        // MVP: allow delete if creator matches OR email is null (to avoid blocking while auth is being wired)
        if (email != null && !email.isBlank() && !email.equalsIgnoreCase(post.getCreatedByEmail())) {
            throw new IllegalArgumentException("Only the author can delete this post.");
        }

        post.setDeleted(true);
        post.setUpdatedAt(Instant.now());
        repo.save(post);
    }
}