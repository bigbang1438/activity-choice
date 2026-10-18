package local.activity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
class ActivityController {
    private final ActivityService service;
    private final LocalSecurity security;
    ActivityController(ActivityService service,LocalSecurity security){this.service=service;this.security=security;}
    @GetMapping("/session") Map<String,Object> session(HttpServletRequest request) {
        var session=request.getSession(true); Object username=session.getAttribute("username");
        return ActivityService.map("user",username==null?null:Map.of("username",username),"csrfToken",LocalSecurity.csrf(session),"csrfHeader",LocalSecurity.CSRF);
    }
    @PostMapping("/register") ResponseEntity<Map<String,String>> register(@RequestBody ActivityService.Credentials input,HttpServletRequest request) {
        security.authLimit(service.validateUsername(input.username()),request.getRemoteAddr());
        String username=service.register(input); LocalSecurity.login(request,username);
        return ResponseEntity.status(201).body(Map.of("username",username));
    }
    @PostMapping("/login") Map<String,String> login(@RequestBody ActivityService.Credentials input,HttpServletRequest request) {
        security.authLimit(service.validateUsername(input.username()),request.getRemoteAddr());
        String username=service.authenticate(input); LocalSecurity.login(request,username); return Map.of("username",username);
    }
    @PostMapping("/logout") ResponseEntity<Void> logout(HttpServletRequest request) { var session=request.getSession(false); if(session!=null)session.invalidate(); return ResponseEntity.noContent().build(); }
    @GetMapping("/groups") Object groups(HttpServletRequest request){return service.groups(LocalSecurity.username(request));}
    @PostMapping("/groups") ResponseEntity<?> create(@RequestBody ActivityService.GroupInput input,HttpServletRequest request){return ResponseEntity.status(201).body(service.createGroup(LocalSecurity.username(request),input));}
    @GetMapping("/groups/{id}") Object group(@PathVariable String id,HttpServletRequest request){return service.getGroup(id,LocalSecurity.username(request));}
    @PostMapping("/groups/{id}/invitations") ResponseEntity<?> invitation(@PathVariable String id,@RequestBody ActivityService.InviteInput input,HttpServletRequest request){return ResponseEntity.status(201).body(service.createInvitation(id,LocalSecurity.username(request),input));}
    @DeleteMapping("/groups/{id}/invitations/{invitationId}") ResponseEntity<Void> revokeInvitation(@PathVariable String id,@PathVariable String invitationId,HttpServletRequest request){service.revokeInvitation(id,invitationId,LocalSecurity.username(request));return ResponseEntity.noContent().build();}
    @PostMapping("/invitations/claim") Object claim(@RequestBody ActivityService.ClaimInput input,HttpServletRequest request){return service.claim(LocalSecurity.username(request),input);}
    @PostMapping("/groups/{id}/candidates") ResponseEntity<?> candidate(@PathVariable String id,@RequestBody ActivityService.CandidateInput input,HttpServletRequest request){return ResponseEntity.status(201).body(service.createCandidate(id,LocalSecurity.username(request),input));}
    @PutMapping("/groups/{id}/candidates/{candidateId}") Object update(@PathVariable String id,@PathVariable String candidateId,@RequestBody ActivityService.CandidateInput input,HttpServletRequest request){return service.updateCandidate(id,candidateId,LocalSecurity.username(request),input);}
    @PutMapping("/groups/{id}/candidates/{candidateId}/preference") Object preference(@PathVariable String id,@PathVariable String candidateId,@RequestBody ActivityService.PreferenceInput input,HttpServletRequest request){return service.preference(id,candidateId,LocalSecurity.username(request),input);}
    @DeleteMapping("/groups/{id}/candidates/{candidateId}/preference") Object withdraw(@PathVariable String id,@PathVariable String candidateId,HttpServletRequest request){return service.withdrawPreference(id,candidateId,LocalSecurity.username(request));}
    @PutMapping("/groups/{id}/decision") Object decide(@PathVariable String id,@RequestBody ActivityService.DecisionInput input,HttpServletRequest request){return service.decide(id,LocalSecurity.username(request),input);}
    @PostMapping("/groups/{id}/publications") ResponseEntity<?> publish(@PathVariable String id,@RequestBody(required=false) Map<String,Object> input,HttpServletRequest request){
        if(input!=null&&!input.isEmpty()) throw ApiError.invalid("发布只使用服务端定案，不接受身份或内容字段");
        return ResponseEntity.status(201).body(service.publish(id,LocalSecurity.username(request)));
    }
    @DeleteMapping("/groups/{id}/publications/{pid}") ResponseEntity<Void> revoke(@PathVariable String id,@PathVariable String pid,HttpServletRequest request){service.revokePublication(id,pid,LocalSecurity.username(request));return ResponseEntity.noContent().build();}
    @GetMapping("/publications/{pid}") Object publication(@PathVariable String pid){return service.publication(pid);}
}
