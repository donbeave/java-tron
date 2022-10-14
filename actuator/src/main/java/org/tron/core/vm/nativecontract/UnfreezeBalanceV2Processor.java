package org.tron.core.vm.nativecontract;

import static org.tron.core.actuator.ActuatorConstant.ACCOUNT_EXCEPTION_STR;
import static org.tron.core.actuator.ActuatorConstant.STORE_NOT_EXIST;
import static org.tron.core.config.Parameter.ChainConstant.FROZEN_PERIOD;
import static org.tron.core.config.Parameter.ChainConstant.TRX_PRECISION;

import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import java.util.Iterator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.DecodeUtil;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.VotesCapsule;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.vm.nativecontract.param.UnfreezeBalanceV2Param;
import org.tron.core.vm.repository.Repository;
import org.tron.protos.Protocol;
import org.tron.protos.contract.Common;

@Slf4j(topic = "VMProcessor")
public class UnfreezeBalanceV2Processor {

  private static final int UNFREEZE_MAX_TIMES = 16;

  public void validate(UnfreezeBalanceV2Param param, Repository repo)
      throws ContractValidateException {
    if (repo == null) {
      throw new ContractValidateException(STORE_NOT_EXIST);
    }

    byte[] ownerAddress = param.getOwnerAddress();
    AccountCapsule accountCapsule = repo.getAccount(ownerAddress);
    DynamicPropertiesStore dynamicStore = repo.getDynamicPropertiesStore();
    if (dynamicStore.getUnfreezeDelayDays() == 0) {
      throw new ContractValidateException("Not support UnfreezeV2 transaction,"
          + " need to be opened by the committee");
    }
    if (!DecodeUtil.addressValid(ownerAddress)) {
      throw new ContractValidateException("Invalid address");
    }
    if (accountCapsule == null) {
      String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);
      throw new ContractValidateException(
          ACCOUNT_EXCEPTION_STR + readableOwnerAddress + "] does not exist");
    }
    long now = dynamicStore.getLatestBlockHeaderTimestamp();
    int unfreezingCount = accountCapsule.getUnfreezingV2Count(now);
    if (UNFREEZE_MAX_TIMES <= unfreezingCount) {
      throw new ContractValidateException("Invalid unfreeze operation, unfreezing times is over limit");
    }
    switch (param.getResourceType()) {
      case BANDWIDTH:
        // validate frozen balance
        if (!this.checkExistFrozenBalance(accountCapsule, Common.ResourceCode.BANDWIDTH)) {
          throw new ContractValidateException("no frozenBalance(BANDWIDTH)");
        }
        // check if it is time to unfreeze
        long allowedUnfreezeCount = accountCapsule.getFrozenList().stream()
            .filter(frozen -> frozen.getExpireTime() <= now).count();
        if (allowedUnfreezeCount <= 0) {
          throw new ContractValidateException("It's not time to unfreeze(BANDWIDTH).");
        }
        break;
      case ENERGY:
        Protocol.Account.Frozen frozenForEnergy = accountCapsule.getAccountResource()
            .getFrozenBalanceForEnergy();
        // validate frozen balance
        if (!this.checkExistFrozenBalance(accountCapsule, Common.ResourceCode.ENERGY)) {
          throw new ContractValidateException("no frozenBalance(Energy)");
        }
        // check if it is time to unfreeze
        if (frozenForEnergy.getExpireTime() > now) {
          throw new ContractValidateException("It's not time to unfreeze(Energy).");
        }
        break;
      case TRON_POWER:
        if (dynamicStore.supportAllowNewResourceModel()) {
          if (!this.checkExistFrozenBalance(accountCapsule, Common.ResourceCode.TRON_POWER)) {
            throw new ContractValidateException("no frozenBalance(TronPower)");
          }
        } else {
          throw new ContractValidateException("ResourceCode error.valid ResourceCode[BANDWIDTH、Energy]");
        }
        break;
      default:
        throw new ContractValidateException("ResourceCode error."
            + "valid ResourceCode[BANDWIDTH、Energy]");
    }

    if (!checkUnfreezeBalance(accountCapsule, param.getUnfreezeBalance(), param.getResourceType())) {
      throw new ContractValidateException(
          "Invalid unfreeze_balance, freezing resource[" + param.getResourceType() + "] is not enough"
      );
    }
  }

  private boolean checkUnfreezeBalance(
      AccountCapsule accountCapsule, long unfreezeBalance, Common.ResourceCode freezeType)  {
    long frozenBalance = 0L;
    List<Protocol.Account.FreezeV2> freezeV2List = accountCapsule.getFrozenV2List();
    for (Protocol.Account.FreezeV2 freezeV2 : freezeV2List) {
      if (freezeV2.getType().equals(freezeType)) {
        frozenBalance = freezeV2.getAmount();
        break;
      }
    }

    return unfreezeBalance <= frozenBalance;
  }

  public boolean checkExistFrozenBalance(AccountCapsule accountCapsule, Common.ResourceCode freezeType) {
    boolean checkOk = false;
    long frozenBalance;
    List<Protocol.Account.FreezeV2> frozenV2List = accountCapsule.getFrozenV2List();
    for (Protocol.Account.FreezeV2 frozenV2 : frozenV2List) {
      if (frozenV2.getType().equals(freezeType)) {
        frozenBalance = frozenV2.getAmount();
        if (frozenBalance > 0) {
          checkOk = true;
          break;
        }
      }
    }
    return checkOk;
  }

  public void execute(UnfreezeBalanceV2Param param, Repository repo) {
    byte[] ownerAddress = param.getOwnerAddress();
    long unfreezeBalance = param.getUnfreezeBalance();

    AccountCapsule accountCapsule = repo.getAccount(ownerAddress);
    long now = repo.getDynamicPropertiesStore().getLatestBlockHeaderTimestamp();

    this.unfreezeExpire(accountCapsule, now);

    this.updateAccountFrozenInfo(param.getResourceType(), accountCapsule, unfreezeBalance);

    long expireTime = this.calcUnfreezeExpireTime(now, repo);
    accountCapsule.addUnfrozenV2List(param.getResourceType(), unfreezeBalance, expireTime);

    this.updateTotalResourceWeight(param.getResourceType(), unfreezeBalance, repo);
    this.clearVotes(accountCapsule, param.getResourceType(), ownerAddress, repo);

    if (repo.getDynamicPropertiesStore().supportAllowNewResourceModel()
        && !accountCapsule.oldTronPowerIsInvalid()) {
      accountCapsule.invalidateOldTronPower();
    }

    repo.updateAccount(accountCapsule.createDbKey(), accountCapsule);
  }

  private void unfreezeExpire(AccountCapsule accountCapsule, long now) {
    long unfreezeBalance = 0L;

    List<Protocol.Account.UnFreezeV2> unFrozenV2List = Lists.newArrayList();
    unFrozenV2List.addAll(accountCapsule.getUnfrozenV2List());
    Iterator<Protocol.Account.UnFreezeV2> iterator = unFrozenV2List.iterator();

    while (iterator.hasNext()) {
      Protocol.Account.UnFreezeV2 next = iterator.next();
      if (next.getUnfreezeExpireTime() <= now) {
        unfreezeBalance += next.getUnfreezeAmount();
        iterator.remove();
      }
    }

    accountCapsule.setInstance(
        accountCapsule.getInstance().toBuilder()
            .setBalance(accountCapsule.getBalance() + unfreezeBalance)
            .clearUnfrozenV2()
            .addAllUnfrozenV2(unFrozenV2List).build()
    );
  }

  private void updateAccountFrozenInfo(
      Common.ResourceCode freezeType, AccountCapsule accountCapsule, long unfreezeBalance) {
    List<Protocol.Account.FreezeV2> freezeV2List = accountCapsule.getFrozenV2List();
    for (int i = 0; i < freezeV2List.size(); i++) {
      if (freezeV2List.get(i).getType().equals(freezeType)) {
        Protocol.Account.FreezeV2 freezeV2 =  Protocol.Account.FreezeV2.newBuilder()
            .setAmount(freezeV2List.get(i).getAmount() - unfreezeBalance)
            .setType(freezeV2List.get(i).getType())
            .build();
        accountCapsule.updateFrozenV2List(i, freezeV2);
        break;
      }
    }
  }

  private long calcUnfreezeExpireTime(long now, Repository repo) {
    long unfreezeDelayDays = repo.getDynamicPropertiesStore().getUnfreezeDelayDays();

    return now + unfreezeDelayDays * FROZEN_PERIOD;
  }

  public void updateTotalResourceWeight(Common.ResourceCode freezeType,
                                        long unfreezeBalance,
                                        Repository repo) {
    DynamicPropertiesStore dynamicStore = repo.getDynamicPropertiesStore();
    switch (freezeType) {
      case BANDWIDTH:
        dynamicStore.addTotalNetWeight(-unfreezeBalance / TRX_PRECISION);
        break;
      case ENERGY:
        dynamicStore.addTotalEnergyWeight(-unfreezeBalance / TRX_PRECISION);
        break;
      case TRON_POWER:
        dynamicStore.addTotalTronPowerWeight(-unfreezeBalance / TRX_PRECISION);
        break;
      default:
        //this should never happen
        break;
    }
  }

  private void clearVotes(
      AccountCapsule accountCapsule, Common.ResourceCode freezeType, byte[] ownerAddress, Repository repo) {

    boolean needToClearVote = true;
    if (repo.getDynamicPropertiesStore().supportAllowNewResourceModel()
        && accountCapsule.oldTronPowerIsInvalid()) {
      switch (freezeType) {
        case BANDWIDTH:
        case ENERGY:
          needToClearVote = false;
          break;
        default:
          break;
      }
    }

    if (needToClearVote) {
      VotesCapsule votesCapsule = repo.getVotes(ownerAddress);
      if (votesCapsule == null) {
        votesCapsule = new VotesCapsule(ByteString.copyFrom(ownerAddress),
            accountCapsule.getVotesList());
      }
      accountCapsule.clearVotes();
      votesCapsule.clearNewVotes();
      repo.updateVotes(ownerAddress, votesCapsule);
    }
  }
}
