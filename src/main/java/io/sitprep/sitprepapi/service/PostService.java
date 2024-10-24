package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.transaction.Transactional;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

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

    // Add or update reaction
    @Transactional
    public void addReaction(Post post, String reaction) {
        post.getReactions().put(reaction, post.getReactions().getOrDefault(reaction, 0) + 1);
        postRepo.save(post);
    }
}
