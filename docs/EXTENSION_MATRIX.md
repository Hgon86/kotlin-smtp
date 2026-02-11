# Extension Point Matrix

Use this table to quickly decide which contract to implement.

| Goal | Implement | Register As | Called In | Notes |
|---|---|---|---|---|
| Replace inbound message persistence | `MessageStore` | Bean of type `MessageStore` | Protocol handler after DATA/BDAT | Return stored path used by downstream flow |
| Replace sent-mail archive backend | `SentMessageStore` | Bean of type `SentMessageStore` | Submission path when archive policy matches | Good place for S3/DB archive |
| Replace auth backend | `AuthService` | Bean of type `AuthService` | AUTH command flow | Keep verification fast |
| Change local vs external domain decision | `InboundRoutingPolicy` | Bean of type `InboundRoutingPolicy` | Delivery decision phase | Can be DB/service backed |
| Add user lookup logic (VRFY) | `SmtpUserHandler` | Bean of type `SmtpUserHandler` | VRFY command | Return empty list if not found |
| Add EXPN list expansion logic | `SmtpMailingListHandler` | Bean of type `SmtpMailingListHandler` | EXPN command | Use policy controls in production |
| Customize transaction behavior | `SmtpProtocolHandler` factory | `SmtpServerBuilder.useProtocolHandlerFactory` (core usage) | MAIL/RCPT/DATA hooks | Advanced use-case |
| Control relay allow/deny | `RelayAccessPolicy` | Bean of type `RelayAccessPolicy` | Before outbound relay | Primary open-relay defense point |
| Replace outbound relay transport | `MailRelay` | Bean of type `MailRelay` | External delivery path | Default uses Jakarta Mail relay stack |
| Customize relay route resolution | `RelayRouteResolver` | Bean of type `RelayRouteResolver` | Per-recipient relay routing | Exact domain > wildcard > default route |
| Customize DSN generation/sending | `DsnSender` | Bean of type `DsnSender` | Failure notification flow | Use with `DsnStore` |
| Customize spool metadata backend | `SpoolMetadataStore` | Bean of type `SpoolMetadataStore` | Spool queue processing | File/Redis defaults provided |
| Customize spool locking backend | `SpoolLockManager` | Bean of type `SpoolLockManager` | Spool queue processing | File/Redis defaults provided |
| Hook events to external systems | `SmtpEventHook` | Bean(s) of type `SmtpEventHook` | Session/message lifecycle points | Hooks are non-fatal by default |

## Bean Override Rule

- Starter defaults are created with `@ConditionalOnMissingBean`.
- Registering your bean with the same type replaces the default.

## Where defaults come from

- Base starter defaults:
  - `kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/config/KotlinSmtpAutoConfiguration.kt`
- Relay starter defaults:
  - `kotlin-smtp-relay-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/relay/config/KotlinSmtpRelayAutoConfiguration.kt`
