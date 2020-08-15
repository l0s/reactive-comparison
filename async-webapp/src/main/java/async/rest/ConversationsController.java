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
package async.rest;

import static async.repository.ConversationCursor.Direction.AFTER;
import static async.repository.ConversationCursor.Direction.BEFORE;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

import java.nio.charset.Charset;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import async.repository.ConversationCursor;
import async.repository.ConversationRepository;
import async.repository.UserRepository;
import domain.Message;

@RestController
@RequestMapping("/conversations")
public class ConversationsController {

    private static final Encoder encoder = Base64.getUrlEncoder();
    private static final Decoder decoder = Base64.getUrlDecoder();
    private static final Charset charset = Charset.forName("UTF-8");

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
    public Callable<ResponseEntity<ConversationDto>> getConversation(@PathVariable final String conversationId) {
        return () -> {
            final var conversation = getRepository().findConversation(UUID.fromString(conversationId));
            if (conversation == null) {
                return ResponseEntity.notFound().build();
            }
            final var dto = new ConversationDto();
            dto.setId(conversation.getId().toString());
            dto.add(linkTo(methodOn(getClass()).getMessages(conversationId, null, "")).withRel("messages"));
            return ResponseEntity.ok(dto);
        };
    }

    public Callable<ResponseEntity<ConversationListDto>> getConversations(final String userId, Integer limitParameter,
            final String cursor) {
        if (limitParameter == null || limitParameter < 0) {
            limitParameter = 8;
        } else if (limitParameter > 16) {
            limitParameter = 16;
        }
        final var limit = limitParameter;

        return () -> {
            final var user = getUserRepository().findById(UUID.fromString(userId));
            if (user == null) {
                return ResponseEntity.notFound().build();
            }

            final ConversationCursor cursorObject = parseConversationCursor(cursor);
            final var conversations = getRepository().findConversations(user, limit, cursorObject);
            final var conversationDtos = conversations.stream().map(conversation -> {
                final var dto = new ConversationDto();
                final var id = conversation.getId().toString();
                dto.setId(id);
                final var link = linkTo(methodOn(getClass()).getConversation(id)).withSelfRel();
                dto.add(link);
                return dto;
            }).collect(Collectors.toList());
            final var retval = new ConversationListDto();
            retval.setConversations(conversationDtos);
            if (conversationDtos.size() > 0) {
                final var first = conversations.get(0);
                final var last = conversations.get(conversations.size() - 1);
                retval.add(
                        linkTo(methodOn(UserController.class).getConversations(userId, limit,
                                encode(new ConversationCursor(BEFORE, first.getId())))).withRel("previous"),
                        linkTo(methodOn(UserController.class).getConversations(userId, limit,
                                encode(new ConversationCursor(AFTER, last.getId())))).withRel("next"));
            }
            return ResponseEntity.ok(retval);
        };
    }

    public static class ConversationDto extends RepresentationModel<ConversationDto> {
        private String id;

        public String getId() {
            return id;
        }

        public void setId(final String id) {
            this.id = id;
        }
    }

    public static class ConversationListDto extends RepresentationModel<ConversationListDto> {
        private List<ConversationDto> conversations = new ArrayList<>();

        public List<ConversationDto> getConversations() {
            return conversations;
        }

        public void setConversations(List<ConversationDto> conversations) {
            this.conversations = conversations;
        }
    }

    @GetMapping("/{conversationId}/messages/{id}")
    public Callable<ResponseEntity<Message>> getMessage(@PathVariable final String conversationId,
            @PathVariable final int id) {
        // TODO DTO
        // TODO link to conversation
        
        return () -> {
            final var message = getRepository().findMessage(UUID.fromString(conversationId), id);
            return ResponseEntity.ok(message);
        };
    }

    @GetMapping("/{conversationId}/messages")
    public Callable<ResponseEntity<MessageListDto>> getMessages(@PathVariable final String conversationId,
            @RequestParam(defaultValue = "8") Integer limitParameter,
            @RequestParam(required = false, defaultValue = "") final String cursor) {
        if (limitParameter == null || limitParameter < 0) {
            limitParameter = 8;
        } else if (limitParameter > 16) {
            limitParameter = 16;
        }
        final var limit = limitParameter;

        return () -> {
            final var conversation = getRepository().findConversation(UUID.fromString(conversationId));
            if (conversation == null) {
                return ResponseEntity.notFound().build();
            }

            final var messageIndex = getMessageIndex(cursor);
            if (messageIndex == null) {
                return ResponseEntity.badRequest().build();
            }

            final var messages = getRepository().findMessages(conversation, limit, messageIndex);
            final var dto = new MessageListDto();
            dto.setMessages(messages);

            // add links
            if (!messages.isEmpty()) {
                final var first = messages.get(0);
                if (first.getId() > Integer.MIN_VALUE) {
                    // there *may* be a previous page
                    dto.add(linkTo(methodOn(ConversationsController.class).getMessages(conversationId, limit,
                            createMessageCursor(first.getId() - 1))).withRel("previous"));
                }
            }
            if (messageIndex >= conversation.getNextMessageId()) {
                dto.add(linkTo(methodOn(ConversationsController.class).getMessages(conversationId, limit,
                        createMessageCursor(messageIndex + limit))).withRel("next"));
            }
            dto.add(linkTo(methodOn(getClass()).getConversation(conversationId)).withRel("conversation"));
            dto.add(linkTo(methodOn(getClass()).getMessages(conversationId, limit, cursor)).withRel("self"));
            return ResponseEntity.ok(dto);
        };
    }

    public static class MessageListDto extends RepresentationModel<MessageListDto> {
        private List<Message> messages;

        public List<Message> getMessages() {
            return messages;
        }

        public void setMessages(final List<Message> messages) {
            Objects.requireNonNull(messages);
            this.messages = messages;
        }
    }

    public Callable<ResponseEntity<Void>> sendMessage(final String fromId, final String toId, final String body) {
        return () -> {
            final var sender = getUserRepository().findById(UUID.fromString(fromId));
            final var recipient = getUserRepository().findById(UUID.fromString(toId));
            final var conversation = getRepository().findOrCreateConversation(sender, recipient);
            // in the future, we can use the sender's time zone
            final var newMessage = new Message(conversation.getId(), UUID.fromString(fromId),
                    OffsetDateTime.now(getClock()), body);
            final var message = getRepository().createMessage(conversation, newMessage);
            final var link = linkTo(methodOn(getClass()).getMessage(conversation.getId().toString(), message.getId()))
                    .withRel("self");
            return ResponseEntity.created(link.toUri()).build();
        };
    }

    public Callable<ResponseEntity<Message>> getMessage(final String senderId, final String recipientId, final int id) {
        return () -> {
            final var sender = getUserRepository().findById(UUID.fromString(senderId));
            final var recipient = getUserRepository().findById(UUID.fromString(recipientId));
            final var message = getRepository().findMessage(sender, recipient, id);
            if (message == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(message);
        };
    }

    protected String createMessageCursor(final int messageIndex) {
        final String string = "id:" + messageIndex;
        return encoder.encodeToString(string.getBytes(charset));
    }

    protected Integer getMessageIndex(final String cursor) {
        if (cursor == null || cursor.isBlank()) {
            // no cursor is provided, get the latest messages
            return Integer.MAX_VALUE;
        }
        final var bytes = decoder.decode(cursor.strip());
        final var string = new String(bytes, charset);
        final var components = string.split(":", 1);
        if( components.length != 2 || !"id".equalsIgnoreCase(components[ 0 ] ) ) {
            // invalid cursor syntax
            return null;
        }
        try {
            return Integer.parseInt(components[1]);
        } catch( final NumberFormatException nfe ) {
            // invalid cursor syntax
            logger.debug("Invalid message cursor: " + cursor + ": " + nfe.getMessage(), nfe);
            return null;
        }
    }

    protected String encode(final ConversationCursor cursor) {
        final var string = "v1." + (cursor.getDirection() == BEFORE ? "before" : "after") + ":"
                + cursor.getReferenceId();
        return encoder.encodeToString(string.getBytes(charset));
    }

    protected ConversationCursor parseConversationCursor(final String cursor) {
        if (cursor == null || cursor.isBlank()) {
            // no cursor provided, get the lowest possible value
            final var lowestPossibleId = new UUID(Long.MIN_VALUE, Long.MIN_VALUE); // not really a valid UUID
            return new ConversationCursor(AFTER, lowestPossibleId);
        }
        final var bytes = decoder.decode(cursor.strip());
        final var string = new String(bytes, charset);
        final var components = string.split(":", 1);
        if (components.length != 2) {
            // invalid cursor syntax
            throw new IllegalArgumentException("Invalid cursor: " + cursor);
        }
        final var referenceId = UUID.fromString(components[1]);
        if ("v1.before".contentEquals(components[0])) {
            return new ConversationCursor(BEFORE, referenceId);
        } else if ("v1.after".contentEquals(components[0])) {
            return new ConversationCursor(AFTER, referenceId);
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