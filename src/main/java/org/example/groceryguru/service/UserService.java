package org.example.groceryguru.service;

import org.example.groceryguru.model.User;
import org.example.groceryguru.repository.UserRepo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    private final UserRepo userRepo;


    public UserService(UserRepo userRepo) {
        this.userRepo = userRepo;
    }

    public Optional<User> findById(Long id){
        return userRepo.findById(id);
    }

    public Optional<User>getUserByEmail(String email){
        return userRepo.getUserByEmail(email);
    }

    public boolean emailExists(String email){
        Optional<User> userOptional = userRepo.findByEmail(email);
        if(userOptional.isPresent()){
            return true;
        }
        return false;
    }

}
