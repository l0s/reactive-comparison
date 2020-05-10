package blocking.rest;

import static org.springframework.hateoas.server.reactive.WebFluxLinkBuilder.linkTo;
import static org.springframework.hateoas.server.reactive.WebFluxLinkBuilder.methodOn;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import blocking.repository.UserRepository;
import domain.Message;
import domain.User;

@RestController
@RequestMapping("/users")
public class UserController {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final UserRepository repository;

    private ConversationsController conversationsController;

    @Autowired
    public UserController(final UserRepository repository) {
        Objects.requireNonNull(repository);
        this.repository = repository;
    }

    @GetMapping("/{id}")
    public User getUser(@PathVariable final String id) {
        return repository.findById(UUID.fromString(id));
    }

    @GetMapping("/")
    public ResponseEntity<List<User>> getUsers(@RequestParam(defaultValue = "0") final int pageNumber,
            @RequestParam(defaultValue = "8") final int pageSize, @RequestHeader final HttpHeaders requestHeaders) {
        if (pageNumber < 0) {
            return ResponseEntity.badRequest().build();
        }
        if (pageSize > 16) {
            // don't let clients request unreasonable page sizes
            return getUsers(pageNumber, 16, requestHeaders);
        }
        final var builder = ResponseEntity.ok();
        final var list = repository.findAll(pageNumber, pageSize);
        // FIXME properly construct base url
        final var host = requestHeaders.getHost();
        final var portString = host.getPort() == 80 ? "" : ":" + host.getPort();
        // FIXME programmatically determine scheme / protocol
        final var baseUrl = "http" + "://" + host.getHostString() + portString + "/users/";
        final var linkElements = new LinkedList<String>();
        if (pageNumber > 0) {
            linkElements.add(
                    "<" + baseUrl + "?pageNumber=" + (pageNumber - 1) + "&pageSize=" + pageSize + ">; rel=previous");
        }
        if (list.size() >= pageSize) {
            // there *may* be a next page
            linkElements
                    .add("<" + baseUrl + "?pageNumber=" + (pageNumber + 1) + "&pageSize=" + pageSize + ">; rel=next");
        }
        builder.header("Link", linkElements.toArray(new String[linkElements.size()]));
        return builder.body(list);
    }

    @PostMapping("/")
    public ResponseEntity<Void> createUser(@RequestBody final Map<String, String> userDto,
            @RequestHeader final HttpHeaders requestHeaders) {
        final User user = new User(userDto.get("name"));
        repository.createUser(user);
        final var webfluxLink = linkTo(methodOn(getClass()).getUser(user.getId().toString())).withRel("self");
        final var mono = webfluxLink.toMono();
        final var future = mono.toFuture();
        try {
            final var selfLink = future.get();
            final URI uri = selfLink.toUri();
            // FIXME properly construct base url
            return ResponseEntity.created(new URI("http://localhost:8080").resolve(uri)).build();
        } catch (final ExecutionException | InterruptedException | URISyntaxException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @PostMapping("/{senderId}/messages/outgoing/{recipientId}")
    public ResponseEntity<Void> sendDirectMessage(@PathVariable final String senderId, @PathVariable final String recipientId, @RequestBody final String body) {
        return getConversationsController().sendMessage(senderId, recipientId, body);
    }

    @GetMapping("/{senderId}/messages/outgoing/{recipientId}/{id}")
    public ResponseEntity<Message> getDirectMessage(@PathVariable final String senderId,
            @PathVariable final String recipientId, @PathVariable final int id) {
        return getConversationsController().getMessage(senderId, recipientId, id);
    }

    public ConversationsController getConversationsController() {
        return conversationsController;
    }

    @Autowired
    public void setConversationsController(final ConversationsController conversationsController) {
        Objects.requireNonNull(conversationsController);
        this.conversationsController = conversationsController;
    }
}