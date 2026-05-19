package cn.upcshare.downloadsite.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class SpaController {
    @GetMapping({"/", "/{path:[^\\.]*}"})
    String forward(HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/api")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "forward:/index.html";
    }
}
