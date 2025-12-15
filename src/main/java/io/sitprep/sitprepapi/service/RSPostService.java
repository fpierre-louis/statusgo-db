package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.dto.RSPostDto;

import java.util.List;
import java.util.UUID;

public interface RSPostService {
    List<RSPostDto> getPostsByGroup(String groupId);
    RSPostDto createPost(String groupId, String email, String content);
    void deletePost(UUID postId, String email);
}