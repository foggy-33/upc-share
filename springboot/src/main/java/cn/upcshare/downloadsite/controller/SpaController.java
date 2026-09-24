package cn.upcshare.downloadsite.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class SpaController {
    // 前端单页应用入口：非 API 路径统一转发到 index.html，由 Vue Router 接管页面路由。
    @GetMapping({"/", "/{path:[^\\.]*}", "/forum/posts/{id}", "/users/{uid}"})
    String forward(HttpServletRequest request, HttpServletResponse response) {
        if (request.getRequestURI().startsWith("/api")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        return "forward:/index.html";
    }
}
