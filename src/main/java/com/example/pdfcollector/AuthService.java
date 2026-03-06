package com.example.pdfcollector;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;

public class AuthService {

    static class User {
        public String username;
        public String password;
    }

    static class UsersFile {
        public List<User> users;
    }

    public static boolean authenticate(String username, String password) {

        try {

            InputStream is = AuthService.class.getResourceAsStream("/com/example/pdfcollector/users.json");
            if (is == null) return false;

            ObjectMapper mapper = new ObjectMapper();
            UsersFile file = mapper.readValue(is, UsersFile.class);

            String hash = sha256(password);

            return file.users.stream()
                    .anyMatch(u ->
                            u.username.equals(username)
                                    && u.password.equals(hash));

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static String sha256(String input) throws Exception {

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes());

        StringBuilder hex = new StringBuilder();

        for (byte b : hash) {
            String s = Integer.toHexString(0xff & b);
            if (s.length() == 1) hex.append('0');
            hex.append(s);
        }

        return hex.toString();
    }
}