package bank.application.impl;

import bank.application.TransferService;
import bank.application.types.Result;
import bank.application.types.assemblers.TransferResultAssembler;
import bank.domain.entity.Account;
import bank.domain.entity.Event;
import bank.domain.external.ExchangeRateService;
import bank.domain.external.IdGeneratorService;
import bank.domain.messaging.AuditMessageProducer;
import bank.domain.messaging.CrossBankTransferMessageProducer;
import bank.domain.repository.AccountRepository;
import bank.domain.repository.TriggerRepository;
import bank.domain.service.AccountTransferService;
import bank.domain.service.TriggerService;
import bank.domain.service.impl.AccountTransferServiceImpl;
import bank.domain.service.impl.TriggerServiceImpl;
import bank.domain.types.AuditMessage;
import bank.domain.types.CrossBankReqMessage;
import bank.types.AccountNumber;
import bank.types.Currency;
import bank.types.ExchangeRate;
import bank.types.Money;
import bank.types.UserId;
import bank.types.trigger.EventStatus;
import bank.types.trigger.OpType;
import bank.types.trigger.TransferEventArgs;
import bank.types.command.TransferDelayAtCommand;
import bank.types.command.TransferCommand;
import bank.types.dto.TransferResultDto;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import lombok.Setter;

/**
 * @author ZhengHao Lou
 * Date    2021/10/24
 *
 * Application service
 */
public class TransferServiceImpl implements TransferService {

    // ---------------------------------------------------------------
    // Domain service
    private AccountTransferService accountTransferService = new AccountTransferServiceImpl();

    private TriggerService commandService = new TriggerServiceImpl();

    // ---------------------------------------------------------------
    // Repository
    @Setter
    private TriggerRepository triggerRepository;

    @Setter
    private AccountRepository accountRepository;

    // ---------------------------------------------------------------
    // Infrastructure service
    @Setter
    private ExchangeRateService exchangeRateService;

    @Setter
    private IdGeneratorService idGeneratorService;

    @Setter
    private AuditMessageProducer auditMessageProducer;

    @Setter
    private CrossBankTransferMessageProducer crossBankTransferMessageProducer;

    @Override
    public TransferResultDto transfer(TransferCommand transferParams) {
        UserId sourceUserId = transferParams.getSourceUserId();
        AccountNumber targetAccountNumber = transferParams.getTargetAccountNumber();
        BigDecimal targetAmount = transferParams.getTargetAmount();
        String targetCurrency = transferParams.getTargetCurrency();

        // 1) ??????????????????????????????????????????????????????
        final Money transferMoney = new Money(targetAmount, Currency.valueOf(targetCurrency));

        Account sourceAccount = accountRepository.find(sourceUserId);
        final ExchangeRate exchangeRate = exchangeRateService.getExchangeRate(sourceAccount.getCurrency(),
            transferMoney.getCurrency());
        Account targetAccount = accountRepository.find(targetAccountNumber);

        // 2) ????????????????????????????????????????????????????????????????????????????????????????????????
        accountTransferService.transfer(sourceAccount, targetAccount, transferMoney, exchangeRate);
        accountRepository.save(sourceAccount);
        accountRepository.save(targetAccount);

        // 3) ?????????e.g. ??????????????????
        AuditMessage message = new AuditMessage(sourceUserId, sourceAccount.getAccountNumber(),
            targetAccount.getAccountNumber(), transferMoney, new Date());
        auditMessageProducer.send(message);

        return TransferResultAssembler.INSTANCE.assemble(sourceAccount, transferMoney, true);
    }

    @Override
    public Result<Boolean> transferDelayAt(TransferDelayAtCommand transferDelayAtCommand) {
        // 1) ??????????????????
        TransferEventArgs args = new TransferEventArgs(transferDelayAtCommand.getTransferCommand());
        long eventId = idGeneratorService.id();
        Event event = new Event(eventId, OpType.TRANSFER, args, EventStatus.PENDING);
        triggerRepository.save(event);

        // 2) ???????????????????????????????????????
        commandService.addDelayAt(event, transferDelayAtCommand.getTimestamp());

        return Result.ok();
    }

    @Override
    public Result<String> cancelDelayedTransfer(UserId sourceUserId, Long commandId) {
        // 1) ????????????
        Account account = accountRepository.find(sourceUserId);
        Event command = triggerRepository.find(account.getAccountNumber(), commandId);
        if (command == null) {
            return Result.failed("Can not find record.");
        }
        if (command.closed()) {
            return Result.failed("Command has been closed.");
        }

        // 2) ??????command?????????
        command.setStatus(EventStatus.CANCELED);
        triggerRepository.save(command);

        // 3) ???timer?????????command
        commandService.del(commandId);
        return Result.ok();
    }

    // ?????????????????????????????????????????????????????????, e.g. ????????????????????????????????????????????????
    @Override
    public Result<Boolean> triggerDelayedCommand(long nowInMillis) {
        // 1) ?????????????????????command
        List<Event> triggeredEvents = commandService.schedule(nowInMillis);

        // 2) ????????????command
        for (Event event : triggeredEvents) {
            switch (event.getOpType()) {
                case TRANSFER:
                default:
                    this.doDelayedTransfer(event);
            }
        }
        return Result.ok();
    }

    @Override
    public Result<Boolean> transferInterBank(UserId sourceUserId, AccountNumber targetAccountNumber,
                                             BigDecimal targetAmount, String targetCurrency, String targetBank) {
        // 1) ??????????????????????????????????????????????????????
        final Money transferMoney = new Money(targetAmount, Currency.valueOf(targetCurrency));
        Account sourceAccount = accountRepository.find(sourceUserId);
        Account targetAccount = accountRepository.find(targetAccountNumber);

        // 2) ????????????????????????
        Long transactionId = idGeneratorService.id();
        CrossBankReqMessage message = new CrossBankReqMessage(transactionId, targetBank, sourceAccount.getAccountNumber(),
            targetAccount.getAccountNumber(), transferMoney, new Date());
        crossBankTransferMessageProducer.send(message);

        return Result.ok();
    }

    @Override
    public Result<Boolean> handleCrossBankTransferResult(Long transactionId, AccountNumber source,
                                                         BigDecimal targetAmount, Boolean result) {
        return null;
    }

    private void doDelayedTransfer(Event event) {
        // 1) ????????????????????????
        Event cmdInDatabase = triggerRepository.find(event.getEventId());
        if (cmdInDatabase.getStatus() != EventStatus.PENDING) {
            return;
        }

        // 2) ????????????
        TransferEventArgs args = (TransferEventArgs) event.getArgs();
        this.transfer(args.getTransferCommand());

        // 3) ????????????
        cmdInDatabase.setStatus(EventStatus.TRIGGERED);
        triggerRepository.save(event);
    }

}
