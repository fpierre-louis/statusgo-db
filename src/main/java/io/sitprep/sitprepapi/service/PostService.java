package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;


import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PostService {

    @Autowired
    private PostRepo postRepo;

    @Transactional
    public Post createPost(Post post, MultipartFile imageFile) throws IOException {
        if (imageFile != null && !imageFile.isEmpty()) {
            post.setImage(imageFile.getBytes()); // Save image as byte array
        }
        return postRepo.save(post);
    }

    @Transactional
    public Post updatePost(Post post, MultipartFile imageFile) throws IOException {
        if (imageFile != null && !imageFile.isEmpty()) {
            post.setImage(imageFile.getBytes()); // Update image if provided
        }
        return postRepo.save(post);
    }

    public List<Post> getPostsByGroupId(Long groupId) {
        return postRepo.findByGroupId(groupId);
    }

    public Optional<Post> getPostById(Long id) {
        return postRepo.findById(id);
    }

    public void deletePost(Long id) {
        postRepo.deleteById(id);
    }
}