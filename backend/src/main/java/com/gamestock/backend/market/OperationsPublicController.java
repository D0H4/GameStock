package com.gamestock.backend.market;

import com.gamestock.backend.auth.AuthService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;

@RestController
@RequestMapping("/api")
public class OperationsPublicController {
    private final AdminContentService content;
    private final AuthService auth;
    public OperationsPublicController(AdminContentService content,AuthService auth){this.content=content;this.auth=auth;}
    @GetMapping("/content") public List<Map<String,Object>> content(@RequestParam(required=false) String kind,@RequestParam(defaultValue="0") long before,@RequestParam(defaultValue="50") int limit){return content.content(true,kind,before,limit);}
    @PostMapping("/reports") public Map<String,Object> report(@RequestBody AdminContentService.Report request,@RequestHeader(value="Authorization",required=false) String authorization){return content.report(auth.requireUser(authorization).id(),request);}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String,String>> invalid(IllegalArgumentException error){return ResponseEntity.badRequest().body(Map.of("message",error.getMessage()));}
}
