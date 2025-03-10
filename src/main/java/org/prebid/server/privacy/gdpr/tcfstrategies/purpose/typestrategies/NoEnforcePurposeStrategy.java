package org.prebid.server.privacy.gdpr.tcfstrategies.purpose.typestrategies;

import com.iabtcf.decoder.TCString;
import com.iabtcf.utils.IntIterable;
import org.apache.commons.collections4.CollectionUtils;
import org.prebid.server.privacy.gdpr.model.VendorPermission;
import org.prebid.server.privacy.gdpr.model.VendorPermissionWithGvl;
import org.prebid.server.privacy.gdpr.vendorlist.proto.PurposeCode;

import java.util.Collection;
import java.util.List;

public class NoEnforcePurposeStrategy extends EnforcePurposeStrategy {

    public Collection<VendorPermission> allowedByTypeStrategy(PurposeCode purpose,
                                                              TCString tcString,
                                                              Collection<VendorPermissionWithGvl> vendorsForPurpose,
                                                              Collection<VendorPermissionWithGvl> excludedVendors,
                                                              boolean isEnforceVendors) {

        final IntIterable vendorConsent = tcString.getVendorConsent();
        final IntIterable vendorLIConsent = tcString.getVendorLegitimateInterest();

        final List<VendorPermission> allowedVendorPermissions = vendorsForPurpose.stream()
                .map(VendorPermissionWithGvl::getVendorPermission)
                .filter(vendorPermission -> vendorPermission.getVendorId() != null)
                .filter(vendorPermission -> isAllowedByVendorConsent(vendorPermission.getVendorId(), isEnforceVendors,
                        vendorConsent, vendorLIConsent))
                .toList();

        return CollectionUtils.union(allowedVendorPermissions, toVendorPermissions(excludedVendors));
    }

    private boolean isAllowedByVendorConsent(Integer vendorId,
                                             boolean isEnforceVendors,
                                             IntIterable vendorConsent,
                                             IntIterable vendorLIConsent) {

        return !isEnforceVendors || vendorConsent.contains(vendorId) || vendorLIConsent.contains(vendorId);
    }
}
