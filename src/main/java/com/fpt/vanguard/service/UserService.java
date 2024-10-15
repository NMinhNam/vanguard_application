package com.fpt.vanguard.service;

import com.fpt.vanguard.dto.request.UserDtoRequest;
import com.fpt.vanguard.dto.response.UserDtoResponse;

import java.util.List;

public interface UserService {
    List<UserDtoResponse> getAllUser();
    UserDtoResponse getUserByUserName(String userName);
    UserDtoResponse getUserInfo();
    Integer saveUser(UserDtoRequest request);
    Integer deleteUser(String username);
}