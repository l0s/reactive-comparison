/**
 * Copyright © 2020 Carlos Macasaet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactive.rest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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

import domain.Message;
import domain.User;
import dto.ConversationListDto;
import reactive.repository.UserRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserRepository repository;

    private ConversationsController conversationsController;

    @Autowired
    public UserController(final UserRepository repository) {
        Objects.requireNonNull(repository);
        this.repository = repository;
    }

    @GetMapping("/{id}")
    public Mono<User> getUser(@PathVariable final String id) {
        return repository.findById(UUID.fromString(id));
    }

    @GetMapping("/")
    public ResponseEntity<Flux<User>> getUsers(@RequestParam(defaultValue = "0") final int pageNumber,
            @RequestParam(defaultValue = "8") final int pageSize, @RequestHeader final HttpHeaders requestHeaders) {
        if (pageNumber < 0) {
            return ResponseEntity.badRequest().build();
        }
        if (pageSize > 16) {
            // don't let clients request unreasonable page sizes
            return getUsers(pageNumber, 16, requestHeaders);
        }
        final var builder = ResponseEntity.ok();
        final var flux = repository.findAll(pageNumber, pageSize).cache();
        flux.count().subscribe(count -> {
            // FIXME properly construct base url
            final var host = requestHeaders.getHost();
            final var portString = host.getPort() == 80 ? "" : ":" + host.getPort();
            // FIXME programmatically determine scheme / protocol
            var baseUrl = "http" + "://" + host.getHostString() + portString + "/users/";
            final var linkElements = new LinkedList<String>();
            if (pageNumber > 0) {
                linkElements.add("<" + baseUrl + "?pageNumber=" + (pageNumber - 1) + "&pageSize=" + pageSize
                        + ">; rel=previous");
            }
            if (count >= pageSize) {
                // there *may* be a next page
                linkElements.add("<" + baseUrl + "?pageNumber=" + (pageNumber + 1) + "&pageSize=" + pageSize
                        + ">; rel=next");
            }
            builder.header("Link", linkElements.toArray(new String[linkElements.size()]));
        });
        return builder.body(flux);
    }

    @PostMapping("/")
    public ResponseEntity<Void> createUser(@RequestBody final Map<String, String> userDto,
            @RequestHeader final HttpHeaders requestHeaders) {
        final User user = new User(userDto.get("name"));
        repository.createUser(user);
        try {
            // FIXME properly construct base url
            final var host = requestHeaders.getHost();
            final var portString = host.getPort() == 80 ? "" : ":" + host.getPort();
            // FIXME programmatically determine scheme / protocol
            final var baseUrl = "http" + "://" + host.getHostString() + portString + "/users/";
            return ResponseEntity.created(new URI(baseUrl + user.getId().toString())).build();
        } catch (final URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @PostMapping("/{senderId}/messages/outgoing/{recipientId}")
    public Mono<ResponseEntity<Void>> sendDirectMessage(@PathVariable final String senderId,
            @PathVariable final String recipientId, @RequestBody final String body) {
        return getConversationsController().sendMessage(senderId, recipientId, body);
    }

    @GetMapping("/{senderId}/messages/outgoing/{recipientId}/{id}")
    public Mono<ResponseEntity<Message>> getDirectMessage(@PathVariable final String senderId,
            @PathVariable final String recipientId, @PathVariable final int id) {
        return getConversationsController().getMessage(senderId, recipientId, id);
    }

    @GetMapping("/{userId}/conversations")
    public Mono<ResponseEntity<ConversationListDto>> getConversations(@PathVariable final String userId, @RequestParam(defaultValue = "8") Integer limit, @RequestParam(required = false) final String cursor) {
        return getConversationsController().getConversations(userId, limit, cursor);
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