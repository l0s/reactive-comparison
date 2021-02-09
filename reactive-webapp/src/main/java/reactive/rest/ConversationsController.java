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

import static org.springframework.hateoas.server.reactive.WebFluxLinkBuilder.linkTo;
import static org.springframework.hateoas.server.reactive.WebFluxLinkBuilder.methodOn;
import static repository.Direction.AFTER;
import static repository.Direction.BEFORE;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.hateoas.server.reactive.WebFluxLinkBuilder.WebFluxLink;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import domain.Message;
import dto.ConversationDto;
import dto.ConversationListDto;
import dto.MessageListDto;
import reactive.repository.ConversationRepository;
import reactive.repository.UserRepository;
import reactor.core.publisher.Mono;
import repository.ConversationCursor;
import repository.Direction;

@RestController
@RequestMapping("/conversations")
public class ConversationsController {

    private static final Encoder encoder = Base64.getUrlEncoder();
    private static final Decoder decoder = Base64.getUrlDecoder();
    private static final Charset charset = StandardCharsets.UTF_8;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Clock clock;
    private final ConversationRepository repository;
    private final UserRepository userRepository;

    @Autowired
    public ConversationsController(final Clock clock, final ConversationRepository repository,
            final UserRepository userRepository) {
        Objects.requireNonNull(clock);
        Objects.requireNonNull(repository);
        Objects.requireNonNull(userRepository);
        this.clock = clock;
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @GetMapping("/{conversationId}")
    public Mono<ResponseEntity<ConversationDto>> getConversation(@PathVariable final String conversationId) {
        return getRepository().findConversation(UUID.fromString(conversationId)).flatMap(conversation -> {
            final String id = conversation.getId().toString();
            final var webfluxLink = linkTo(
                    methodOn(getClass()).getMessages(id, null, "")).withRel("messages");
            return webfluxLink.toMono().map(messagesLink -> {
                final var dto = new ConversationDto();
                dto.setId(id);
                dto.add(messagesLink);
                return ResponseEntity.ok(dto);
            });
        }).switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(null)));
    }

    public Mono<ResponseEntity<ConversationListDto>> getConversations(final String userId, final Integer limit,
            final String cursor) {
        final ConversationCursor cursorObject = parseConversationCursor(cursor);
        if (limit == null || limit < 0) {
            return getConversations(userId, 8, cursor);
        } else if (limit > 16) {
            return getConversations(userId, 16, cursor);
        }
        return getUserRepository().findById(UUID.fromString(userId))
            .flatMap(user -> getRepository().findConversations(Mono.just(user), limit, cursorObject).collectList()
                .map(conversations -> {
                    final var dtos = conversations.stream().map(conversation -> {
                        final var id = conversation.getId().toString();
                        final var dto = new ConversationDto();
                        dto.setId(id);
                        dto.add(linkTo(methodOn(getClass()).getConversation(id)).withSelfRel().toMono()
                                .toFuture().join());
                        return dto;
                    }).collect(Collectors.toList());
                    final var listDto = new ConversationListDto();
                    listDto.setConversations(dtos);
                    if (conversations.size() > 0) {
                        final var first = conversations.get(0);
                        final var last = conversations.get(conversations.size() - 1);
                        listDto.add(
                                linkTo(methodOn(UserController.class).getConversations(userId, limit,
                                        encode(new ConversationCursor(BEFORE, first.getId()))))
                                                .withRel("previous").toMono().toFuture().join(),
                                linkTo(methodOn(UserController.class).getConversations(userId, limit,
                                        encode(new ConversationCursor(AFTER, last.getId()))))
                                                .withRel("next").toMono().toFuture().join());
                    }

                    return ResponseEntity.ok(listDto);
                }))
            .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(null)));
    }

    @GetMapping("/{conversationId}/messages/{id}")
    public Mono<ResponseEntity<Message>> getMessage(@PathVariable final String conversationId,
            @PathVariable final int id) {
        return getRepository().findMessage(UUID.fromString(conversationId), id).map(ResponseEntity::ok);
    }

    @GetMapping("/{conversationId}/messages")
    public Mono<ResponseEntity<MessageListDto>> getMessages(@PathVariable final String conversationId,
            @RequestParam(defaultValue = "8") final Integer limit,
            @RequestParam(required = false, defaultValue = "") final String cursor) {
        if (limit == null || limit < 0) {
            return getMessages(conversationId, 8, cursor);
        } else if (limit > 16) {
            return getMessages(conversationId, 16, cursor);
        }

        final var messageIndex = getMessageIndex(cursor);
        if (messageIndex == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }

        final var emptyHandler = Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body((MessageListDto) null));
        return getRepository().findConversation(UUID.fromString(conversationId)).flatMap(conversation -> getRepository().findMessages(Mono.just(conversation), limit, messageIndex)
                .collectList()
                .map(messages -> {
                    final var dto = new MessageListDto();
                    dto.setMessages(messages);
                    // add links
                    final var links = new LinkedBlockingQueue<WebFluxLink>();
                    if (!messages.isEmpty()) {
                        final var first = messages.get(0);
                        if (first.getId() > Integer.MIN_VALUE) {
                            // there *may* be a previous page
                            links.add(linkTo(methodOn(ConversationsController.class).getMessages(conversationId, limit,
                                    createCursor(first.getId() - 1))).withRel("previous"));
                        }
                    }
                    if (messageIndex >= conversation.getNextMessageId()) {
                        links.add(linkTo(methodOn(ConversationsController.class).getMessages(conversationId, limit,
                                createCursor(messageIndex + limit))).withRel("next"));
                    }
                    links.add(linkTo(methodOn(getClass()).getConversation(conversationId)).withRel("conversation"));
                    links.stream().map(WebFluxLink::toMono).map(Mono::toFuture).map(CompletableFuture::join).forEach(dto::add);

                    return ResponseEntity.ok(dto);
                 }))
        .switchIfEmpty(emptyHandler);
    }

    public Mono<ResponseEntity<Void>> sendMessage(final String fromId, final String toId, final String body) {
        final var sender = getUserRepository().findById(UUID.fromString(fromId));
        final var recipient = getUserRepository().findById(UUID.fromString(toId));
        final var conversation = getRepository().findOrCreateConversation(sender, recipient).cache();
        final var message = conversation.map(c -> {
            // in the future, we can use the sender's time zone
            return new Message(c.getId(), UUID.fromString(fromId),
                    OffsetDateTime.now(getClock()), body);
        });

        return getRepository().createMessage(conversation, message)
        .flatMap(m -> linkTo(
                methodOn(getClass()).getMessage(m.getConversationId().toString(), m.getId()))
                        .withRel("self").toMono())
        .map(link -> {
            final var locationUri = link.toUri();
            return ResponseEntity.status(HttpStatus.CREATED).header("Location", locationUri.toString());
        })
        .switchIfEmpty(Mono.fromSupplier(() -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)))
        .map(BodyBuilder::build);
    }

    public Mono<ResponseEntity<Message>> getMessage(final String senderId, final String recipientId, final int id) {
        final var sender = getUserRepository().findById(UUID.fromString(senderId));
        final var recipient = getUserRepository().findById(UUID.fromString(recipientId));
        final var message = getRepository().findMessage(sender, recipient, id);
        return message.map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(null)));
    }

    protected String createCursor(final int messageIndex) {
        final String string = "id:" + messageIndex;
        return encoder.encodeToString(string.getBytes(charset));
    }

    protected Integer getMessageIndex(final String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.isBlank()) {
            // no cursor is provided, get the latest messages
            return Integer.MAX_VALUE;
        }
        final var bytes = decoder.decode(cursor.strip());
        final var string = new String(bytes, charset);
        final var components = string.split(":", 1);
        if (components.length != 2 || !"id".equalsIgnoreCase(components[0])) {
            // invalid cursor syntax
            return null;
        }
        try {
            return Integer.parseInt(components[1]);
        } catch (final NumberFormatException nfe) {
            // invalid cursor syntax
            logger.debug("Invalid message cursor: " + cursor + ": " + nfe.getMessage(), nfe);
            return null;
        }
    }

    protected String encode(final ConversationCursor cursor) {
        final var string = "v1." + (cursor.getDirection() == Direction.BEFORE ? "before" : "after") + ":"
                + cursor.getReferenceId();
        return encoder.encodeToString(string.getBytes(charset));
    }

    protected ConversationCursor parseConversationCursor(final String cursor) {
        if (cursor == null || cursor.isBlank()) {
            // no cursor provided, get the lowest possible value
            final var lowestPossibleId = new UUID(Long.MIN_VALUE, Long.MIN_VALUE); // not really a valid UUID
            return new ConversationCursor(Direction.AFTER, lowestPossibleId);
        }
        final var bytes = decoder.decode(cursor.strip());
        final var string = new String(bytes, charset);
        final var components = string.split(":", 1);
        if (components.length != 2) {
            // invalid cursor syntax
            throw new IllegalArgumentException("Invalid cursor: " + cursor);
        }
        final var referenceId = UUID.fromString(components[ 1 ] );
        if( "v1.before".contentEquals(components[ 0 ] ) ) {
            return new ConversationCursor(Direction.BEFORE, referenceId);
        } else if( "v1.after".contentEquals(components[ 0 ] ) ) {
            return new ConversationCursor(Direction.AFTER, referenceId);
        } else {
            throw new IllegalArgumentException("Invalid cursor: " + cursor);
        }
    }

    protected ConversationRepository getRepository() {
        return repository;
    }

    protected Clock getClock() {
        return clock;
    }

    protected UserRepository getUserRepository() {
        return userRepository;
    }

}