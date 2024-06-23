package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class UserInfoService {

    @Autowired
    private UserInfoRepo userInfoRepo;

    public UserInfo saveUser(UserInfo userInfo) {
        return userInfoRepo.save(userInfo);
    }

    public Optional<UserInfo> getUserById(String id) {
        return userInfoRepo.findById(id);
    }

    public Optional<UserInfo> getUserByEmail(String email) {
        return userInfoRepo.findByUserEmail(email);
    }

    public List<UserInfo> getAllUsers() {
        return userInfoRepo.findAll();
    }

    public void deleteUser(String id) {
        userInfoRepo.deleteById(id);
    }
}
