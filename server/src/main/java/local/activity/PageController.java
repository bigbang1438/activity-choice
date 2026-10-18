package local.activity;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class PageController {
    @GetMapping({"/share/{id}","/join","/groups/{id}"}) String page(){return "forward:/index.html";}
}
